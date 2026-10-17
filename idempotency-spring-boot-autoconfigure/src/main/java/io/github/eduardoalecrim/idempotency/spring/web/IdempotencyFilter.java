// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.ClientIdentityResolver;
import io.github.eduardoalecrim.idempotency.Decision;
import io.github.eduardoalecrim.idempotency.IdempotencyEngine;
import io.github.eduardoalecrim.idempotency.problem.Problem;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies the engine's decision to requests that target an idempotent operation (ADR 0010).
 *
 * <p>It runs after the Spring Security filter chain, so the principal is known. Rejections and replays are written
 * here without calling the chain.
 */
public final class IdempotencyFilter extends OncePerRequestFilter {

    private final IdempotencyEngine engine;
    private final IdempotentHandlerResolver resolver;
    private final Set<String> methods;
    private final ClientIdentityResolver<HttpServletRequest> identity;

    /**
     * @param engine the engine
     * @param resolver finds the operation of a request
     * @param methods the methods that can be idempotent, in uppercase
     * @param identity resolves the client identity
     */
    public IdempotencyFilter(
            IdempotencyEngine engine,
            IdempotentHandlerResolver resolver,
            Set<String> methods,
            ClientIdentityResolver<HttpServletRequest> identity) {
        this.engine = engine;
        this.resolver = resolver;
        this.methods = Set.copyOf(methods);
        this.identity = identity;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !methods.contains(request.getMethod().toUpperCase(Locale.ROOT));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        IdempotentOperation operation = resolver.resolve(request);
        if (operation == null) {
            chain.doFilter(request, response);
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        Decision decision;
        try {
            decision = engine.begin(
                    new ServletIncomingRequest(cached, operation.routeTemplate(), identity), operation.policy());
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }

        if (decision instanceof Decision.Reject reject) {
            writeProblem(reject, response);
        } else if (decision instanceof Decision.Replay replay) {
            writeReplay(replay.response(), response);
        } else if (decision instanceof Decision.Proceed proceed) {
            proceed(proceed, cached, response, chain);
        } else {
            chain.doFilter(cached, response);
        }
    }

    private void proceed(
            Decision.Proceed proceed, CachedBodyRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CapturingResponse capture =
                new CapturingResponse(response, (int) proceed.policy().maxResponseBytes());
        try {
            chain.doFilter(request, capture);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            engine.abort(proceed, e);
            throw e;
        }
        if (capture.errorSent()) {
            engine.release(proceed);
        } else {
            engine.finish(proceed, capture.captured());
        }
        capture.sendBody();
    }

    private static void writeProblem(Decision.Reject reject, HttpServletResponse response) throws IOException {
        Problem problem = reject.problem();
        byte[] body = problem.toJson();
        response.setStatus(problem.status());
        reject.headers().forEach(response::setHeader);
        response.setContentType(Problem.MEDIA_TYPE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private static void writeReplay(StoredResponse stored, HttpServletResponse response) throws IOException {
        response.setStatus(stored.status());
        Set<String> written = new HashSet<>();
        for (StoredResponse.Header header : stored.headers()) {
            // The first value replaces what earlier filters set; later values of the same name are added.
            if (written.add(header.name().toLowerCase(Locale.ROOT))) {
                response.setHeader(header.name(), header.value());
            } else {
                response.addHeader(header.name(), header.value());
            }
        }
        response.setHeader(IdempotencyEngine.REPLAYED_HEADER, "true");
        byte[] body = stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
