// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.Idempotent;
import io.github.eduardoalecrim.idempotency.fingerprint.FingerprintStrategy;
import io.github.eduardoalecrim.idempotency.internal.Durations;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyConfigurationException;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanInstantiationException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.BeanFactory;

/** Builds the policy of each operation from the default policy and its annotation or configured route. */
final class OperationPolicies {

    private final IdempotencyPolicy defaults;
    private final BeanFactory beanFactory;
    // One instance per strategy class; bounded by the classes named in annotations.
    private final Map<Class<? extends FingerprintStrategy>, FingerprintStrategy> strategies = new ConcurrentHashMap<>();

    OperationPolicies(IdempotencyPolicy defaults, BeanFactory beanFactory) {
        this.defaults = defaults;
        this.beanFactory = beanFactory;
    }

    /**
     * Returns the policy declared by {@code annotation}.
     *
     * @param handler names the handler in errors, such as {@code OrderController#create}
     * @throws IdempotencyConfigurationException if a duration is malformed, the lease is not shorter than the TTL, or
     *     the fingerprint strategy cannot be created
     */
    IdempotencyPolicy forAnnotation(Idempotent annotation, String handler) {
        List<String> headers = new ArrayList<>(defaults.fingerprintHeaders());
        headers.addAll(Arrays.asList(annotation.fingerprintHeaders()));
        IdempotencyPolicy.Builder builder = defaults.toBuilder()
                .required(annotation.required())
                .scope(annotation.scope())
                .ttl(duration(annotation.ttl(), defaults.ttl(), "ttl", handler))
                .lease(duration(annotation.lease(), defaults.lease(), "lease", handler))
                .fingerprintHeaders(headers);
        if (annotation.fingerprint() != FingerprintStrategy.Default.class) {
            builder.fingerprint(strategy(annotation.fingerprint(), handler));
        }
        return build(builder, "@Idempotent on " + handler);
    }

    /** Returns the policy of a route declared under {@code idempotency.routes}. */
    IdempotencyPolicy forRoute(IdempotencyProperties.Route route, String description) {
        Duration ttl = route.getTtl();
        Duration lease = route.getLease();
        IdempotencyPolicy.Builder builder = defaults.toBuilder()
                .required(route.isRequired())
                .ttl(ttl != null ? ttl : defaults.ttl())
                .lease(lease != null ? lease : defaults.lease());
        return build(builder, description);
    }

    private static IdempotencyPolicy build(IdempotencyPolicy.Builder builder, String source) {
        try {
            return builder.build();
        } catch (IllegalArgumentException e) {
            throw new IdempotencyConfigurationException(
                    "Invalid idempotency policy of " + source + ": " + e.getMessage(),
                    "Fix " + source + ", or the global defaults it inherits.");
        }
    }

    private static Duration duration(String value, Duration fallback, String attribute, String handler) {
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return Durations.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IdempotencyConfigurationException(
                    "Invalid " + attribute + " of @Idempotent on " + handler + ": " + e.getMessage(),
                    "Fix the " + attribute + " attribute of @Idempotent on " + handler + ".");
        }
    }

    private FingerprintStrategy strategy(Class<? extends FingerprintStrategy> type, String handler) {
        return strategies.computeIfAbsent(type, t -> create(t, handler));
    }

    private FingerprintStrategy create(Class<? extends FingerprintStrategy> type, String handler) {
        @Nullable FingerprintStrategy bean = beanFactory.getBeanProvider(type).getIfAvailable();
        if (bean != null) {
            return bean;
        }
        try {
            return BeanUtils.instantiateClass(type);
        } catch (BeanInstantiationException e) {
            throw new IdempotencyConfigurationException(
                    "Cannot create fingerprint strategy " + type.getName() + " of @Idempotent on " + handler + ": "
                            + e.getMessage(),
                    "Declare " + type.getSimpleName() + " as a bean, or give it a public no-argument constructor.");
        }
    }
}
