// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.Idempotent;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyConfigurationException;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Finds the idempotent operation of a request: an {@code @Idempotent} handler, or else a route under
 * {@code idempotency.routes}. Annotation lookups are cached per handler method.
 */
public final class IdempotentHandlerResolver {

    private final Supplier<RequestMappingHandlerMapping> mapping;
    private final OperationPolicies policies;
    private final List<ConfiguredRoute> routes;
    // Keyed by handler method, so it holds one entry per controller method at most.
    private final Map<HandlerKey, Optional<IdempotencyPolicy>> handlers = new ConcurrentHashMap<>();

    IdempotentHandlerResolver(
            Supplier<RequestMappingHandlerMapping> mapping,
            OperationPolicies policies,
            List<IdempotencyProperties.Route> routes) {
        this.mapping = mapping;
        this.policies = policies;
        this.routes = compile(routes, policies);
    }

    /** Returns the operation of {@code request}, if it targets one. */
    public @Nullable IdempotentOperation resolve(HttpServletRequest request) {
        HandlerMethod handler = handlerMethod(request);
        if (handler != null) {
            Optional<IdempotencyPolicy> policy = policyOf(handler);
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (policy.isPresent() && pattern instanceof String routeTemplate) {
                return new IdempotentOperation(routeTemplate, policy.get());
            }
        }
        String method = request.getMethod().toUpperCase(Locale.ROOT);
        PathContainer path = RequestPath.parse(request.getRequestURI(), request.getContextPath())
                .pathWithinApplication();
        for (ConfiguredRoute route : routes) {
            if (route.methods().contains(method) && route.pattern().matches(path)) {
                return new IdempotentOperation(route.pattern().getPatternString(), route.policy());
            }
        }
        return null;
    }

    /**
     * Returns the policy of a handler method: its {@code @Idempotent}, or else its class's; empty when neither is
     * annotated.
     *
     * @throws IdempotencyConfigurationException if the annotation is invalid
     */
    public Optional<IdempotencyPolicy> policyOf(HandlerMethod handler) {
        return handlers.computeIfAbsent(new HandlerKey(handler.getBeanType(), handler.getMethod()), key -> {
            Idempotent annotation = AnnotatedElementUtils.findMergedAnnotation(key.method(), Idempotent.class);
            if (annotation == null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(key.beanType(), Idempotent.class);
            }
            return annotation == null
                    ? Optional.empty()
                    : Optional.of(policies.forAnnotation(annotation, describe(key.beanType(), key.method())));
        });
    }

    /** Returns the operations declared under {@code idempotency.routes}, one per method. */
    public List<DeclaredRoute> configuredRoutes() {
        List<DeclaredRoute> declared = new ArrayList<>();
        for (ConfiguredRoute route : routes) {
            route.methods().stream()
                    .sorted()
                    .forEach(method -> declared.add(
                            new DeclaredRoute(method, route.pattern().getPatternString(), route.policy())));
        }
        return declared;
    }

    /** Names a handler in messages, such as {@code OrderController#create}. */
    static String describe(Class<?> beanType, Method method) {
        return beanType.getSimpleName() + "#" + method.getName();
    }

    // The handler mapping needs the parsed path, which the DispatcherServlet has not set yet in a filter.
    private @Nullable HandlerMethod handlerMethod(HttpServletRequest request) {
        boolean parsed = !ServletRequestPathUtils.hasParsedRequestPath(request);
        if (parsed) {
            ServletRequestPathUtils.parseAndCache(request);
        }
        try {
            HandlerExecutionChain chain = mapping.get().getHandler(request);
            return chain != null && chain.getHandler() instanceof HandlerMethod handler ? handler : null;
        } catch (Exception e) {
            // No match, or a mismatch such as an unsupported media type: the DispatcherServlet reports it.
            return null;
        } finally {
            if (parsed) {
                ServletRequestPathUtils.clearParsedRequestPath(request);
            }
        }
    }

    private static List<ConfiguredRoute> compile(List<IdempotencyProperties.Route> routes, OperationPolicies policies) {
        List<ConfiguredRoute> compiled = new ArrayList<>();
        for (int i = 0; i < routes.size(); i++) {
            IdempotencyProperties.Route route = routes.get(i);
            String property = "idempotency.routes[" + i + "]";
            String path = route.getPath();
            if (path == null || !path.startsWith("/")) {
                throw new IdempotencyConfigurationException(
                        "Invalid value of '" + property + ".path': it must be a path pattern starting with '/'",
                        "Update '" + property + ".path' in the configuration.");
            }
            Set<String> methods = Set.copyOf(route.getMethods().stream()
                    .map(m -> m.trim().toUpperCase(Locale.ROOT))
                    .toList());
            compiled.add(new ConfiguredRoute(
                    methods, PathPatternParser.defaultInstance.parse(path), policies.forRoute(route, property)));
        }
        return List.copyOf(compiled);
    }

    /**
     * An operation declared under {@code idempotency.routes}.
     *
     * @param method the HTTP method, in uppercase
     * @param routeTemplate the path pattern
     * @param policy the operation's policy
     */
    public record DeclaredRoute(String method, String routeTemplate, IdempotencyPolicy policy) {}

    private record HandlerKey(Class<?> beanType, Method method) {}

    private record ConfiguredRoute(Set<String> methods, PathPattern pattern, IdempotencyPolicy policy) {}
}
