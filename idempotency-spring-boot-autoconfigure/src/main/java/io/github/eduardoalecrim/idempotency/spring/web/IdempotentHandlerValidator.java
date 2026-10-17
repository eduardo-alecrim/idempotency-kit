// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.Idempotent;
import io.github.eduardoalecrim.idempotency.internal.Durations;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyConfigurationException;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Checks every idempotent handler once the context is refreshed, and fails startup naming each invalid one:
 * annotations on handlers of other methods (FR-16), streaming or asynchronous handlers (FR-20), and invalid policies
 * such as a lease that is not shorter than the TTL. Valid operations are logged with their policy.
 */
final class IdempotentHandlerValidator implements ApplicationListener<ContextRefreshedEvent> {

    private static final System.Logger LOG = System.getLogger(IdempotentHandlerValidator.class.getName());

    private static final Set<RequestMethod> SAFE_METHODS =
            EnumSet.of(RequestMethod.GET, RequestMethod.HEAD, RequestMethod.OPTIONS, RequestMethod.TRACE);

    private static final List<Class<?>> STREAMING_TYPES = List.of(
            ResponseBodyEmitter.class,
            StreamingResponseBody.class,
            DeferredResult.class,
            Callable.class,
            WebAsyncTask.class,
            CompletionStage.class);

    // Reactive types are checked by name, so the adapter needs neither library.
    private static final List<String> REACTIVE_TYPES =
            List.of("org.reactivestreams.Publisher", "io.smallrye.mutiny.Uni", "io.smallrye.mutiny.Multi");

    private final Supplier<RequestMappingHandlerMapping> mapping;
    private final IdempotentHandlerResolver resolver;
    private final Set<String> methods;

    IdempotentHandlerValidator(
            Supplier<RequestMappingHandlerMapping> mapping, IdempotentHandlerResolver resolver, Set<String> methods) {
        this.mapping = mapping;
        this.resolver = resolver;
        this.methods = Set.copyOf(methods);
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        List<String> errors = new ArrayList<>();
        List<String> operations = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mapping.get().getHandlerMethods().entrySet()) {
            check(entry.getKey(), entry.getValue(), errors, operations);
        }
        for (IdempotentHandlerResolver.DeclaredRoute route : resolver.configuredRoutes()) {
            String operation = route.method() + " " + route.routeTemplate();
            if (!methods.contains(route.method())) {
                errors.add("idempotency.routes: " + operation + " uses " + route.method()
                        + ", which is not in idempotency.methods");
            } else {
                operations.add(operation + " (" + describe(route.policy()) + ")");
            }
        }
        if (!errors.isEmpty()) {
            throw new IdempotencyConfigurationException(
                    "Invalid idempotent operations:\n  - " + String.join("\n  - ", errors),
                    "Fix the handlers or routes listed above, or remove @Idempotent from them.");
        }
        operations.forEach(operation -> LOG.log(Level.INFO, "Idempotent operation " + operation));
    }

    private void check(RequestMappingInfo info, HandlerMethod handler, List<String> errors, List<String> operations) {
        String name = IdempotentHandlerResolver.describe(handler.getBeanType(), handler.getMethod());
        boolean onMethod = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), Idempotent.class);
        if (!onMethod && !AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), Idempotent.class)) {
            return;
        }
        Set<RequestMethod> mapped = info.getMethodsCondition().getMethods();
        List<String> keyAware = new ArrayList<>();
        for (RequestMethod method : mapped) {
            if (methods.contains(method.name())) {
                keyAware.add(method.name());
            }
        }
        if (onMethod) {
            if (mapped.isEmpty()) {
                errors.add(name + " maps every HTTP method, including GET; restrict it to "
                        + String.join(", ", methods.stream().sorted().toList()));
                return;
            }
            for (RequestMethod method : mapped) {
                if (SAFE_METHODS.contains(method)) {
                    errors.add(name + " handles " + method + ", which is safe and cannot be idempotent");
                } else if (!methods.contains(method.name())) {
                    errors.add(name + " handles " + method + ", which is not in idempotency.methods");
                }
            }
        }
        if (keyAware.isEmpty()) {
            return;
        }
        String unsupported = unsupportedReturnType(handler);
        if (unsupported != null) {
            errors.add(name + " returns " + unsupported + "; streaming and asynchronous handlers cannot be idempotent");
            return;
        }
        Optional<IdempotencyPolicy> policy;
        try {
            policy = resolver.policyOf(handler);
        } catch (IdempotencyConfigurationException e) {
            errors.add(String.valueOf(e.getMessage()));
            return;
        }
        policy.ifPresent(p -> {
            for (String pattern : info.getPatternValues()) {
                keyAware.forEach(method -> operations.add(method + " " + pattern + " (" + describe(p) + ")"));
            }
        });
    }

    private static @Nullable String unsupportedReturnType(HandlerMethod handler) {
        ResolvableType type = ResolvableType.forMethodReturnType(handler.getMethod());
        Class<?> raw = type.resolve(Object.class);
        if (HttpEntity.class.isAssignableFrom(raw)) {
            raw = type.as(HttpEntity.class).getGeneric(0).resolve(Object.class);
        }
        for (Class<?> streaming : STREAMING_TYPES) {
            if (streaming.isAssignableFrom(raw)) {
                return raw.getSimpleName();
            }
        }
        for (Class<?> c = raw; c != null && c != Object.class; c = c.getSuperclass()) {
            if (isReactive(c)) {
                return raw.getSimpleName();
            }
        }
        return null;
    }

    private static boolean isReactive(Class<?> type) {
        if (REACTIVE_TYPES.contains(type.getName())) {
            return true;
        }
        for (Class<?> parent : type.getInterfaces()) {
            if (isReactive(parent)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(IdempotencyPolicy policy) {
        return "ttl " + Durations.format(policy.ttl()) + ", lease " + Durations.format(policy.lease()) + ", key "
                + (policy.required() ? "required" : "optional") + ", "
                + policy.scope().name().toLowerCase(Locale.ROOT) + " scope";
    }
}
