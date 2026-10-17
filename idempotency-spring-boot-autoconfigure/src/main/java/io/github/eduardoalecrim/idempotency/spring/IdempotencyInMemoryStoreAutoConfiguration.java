// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties.StoreType;
import io.github.eduardoalecrim.idempotency.store.memory.InMemoryIdempotencyStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The in-memory store, when no other store is configured and {@code idempotency.store.type} is {@code auto} or
 * {@code in-memory}. Store auto-configurations for JDBC and Redis run before this one.
 */
@AutoConfiguration
@ConditionalOnBooleanProperty(name = "idempotency.enabled", matchIfMissing = true)
@ConditionalOnMissingBean(IdempotencyStore.class)
@Conditional(IdempotencyInMemoryStoreAutoConfiguration.OnAutoOrInMemory.class)
public final class IdempotencyInMemoryStoreAutoConfiguration {

    /** The in-memory store, bounded by {@code idempotency.store.in-memory.max-entries}. */
    @Bean
    InMemoryIdempotencyStore idempotencyStore(IdempotencyProperties properties) {
        int maxEntries = properties.getStore().getInMemory().getMaxEntries();
        if (maxEntries < 1) {
            throw IdempotencyConfigurationException.property(
                    "idempotency.store.in-memory.max-entries", "it must be positive");
        }
        return new InMemoryIdempotencyStore(maxEntries);
    }

    static final class OnAutoOrInMemory extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
            StoreType type = Binder.get(context.getEnvironment())
                    .bind("idempotency.store.type", StoreType.class)
                    .orElse(StoreType.AUTO);
            return type == StoreType.AUTO || type == StoreType.IN_MEMORY
                    ? ConditionOutcome.match("idempotency.store.type is " + type)
                    : ConditionOutcome.noMatch("idempotency.store.type is " + type);
        }
    }
}
