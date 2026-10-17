// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.ClientIdentityResolver;
import io.github.eduardoalecrim.idempotency.IncomingRequest;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A servlet request as the engine sees it. */
final class ServletIncomingRequest implements IncomingRequest {

    private final CachedBodyRequest request;
    private final String routeTemplate;
    private final ClientIdentityResolver<HttpServletRequest> identity;

    ServletIncomingRequest(
            CachedBodyRequest request, String routeTemplate, ClientIdentityResolver<HttpServletRequest> identity) {
        this.request = request;
        this.routeTemplate = routeTemplate;
        this.identity = identity;
    }

    @Override
    public String method() {
        return request.getMethod().toUpperCase(Locale.ROOT);
    }

    @Override
    public String path() {
        return request.getRequestURI();
    }

    @Override
    public String routeTemplate() {
        return routeTemplate;
    }

    @Override
    public List<String> headerValues(String name) {
        return Collections.list(request.getHeaders(name));
    }

    @Override
    public @Nullable String contentType() {
        return request.getContentType();
    }

    @Override
    public Optional<String> clientIdentity() {
        return identity.resolve(request).filter(id -> !id.isBlank());
    }

    @Override
    public byte[] body(int maxBytes) {
        return request.body(maxBytes);
    }
}
