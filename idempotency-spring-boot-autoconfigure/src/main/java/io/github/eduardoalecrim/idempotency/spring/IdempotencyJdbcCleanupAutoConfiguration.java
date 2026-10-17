// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import io.github.eduardoalecrim.idempotency.store.jdbc.JdbcIdempotencyStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * The purge job of the JDBC store (FR-26): every {@code idempotency.store.jdbc.cleanup.interval}, in batches of
 * {@code cleanup.batch-size}, unless {@code cleanup.enabled=false}. Times come from the application's {@link Clock}
 * bean when there is one.
 */
@AutoConfiguration(after = IdempotencyJdbcStoreAutoConfiguration.class)
@ConditionalOnClass(JdbcIdempotencyStore.class)
@ConditionalOnBean(JdbcIdempotencyStore.class)
@ConditionalOnBooleanProperty(name = "idempotency.store.jdbc.cleanup.enabled", matchIfMissing = true)
public final class IdempotencyJdbcCleanupAutoConfiguration {

    private static final String PREFIX = IdempotencyJdbcStoreAutoConfiguration.PREFIX + "cleanup.";

    @Bean
    IdempotencyJdbcCleanup idempotencyJdbcCleanup(
            JdbcIdempotencyStore store, IdempotencyProperties properties, ObjectProvider<Clock> clock) {
        IdempotencyProperties.Store.Jdbc.Cleanup cleanup =
                properties.getStore().getJdbc().getCleanup();
        Duration interval = cleanup.getInterval();
        if (interval.isNegative() || interval.isZero()) {
            throw IdempotencyConfigurationException.property(PREFIX + "interval", "it must be positive");
        }
        if (cleanup.getBatchSize() < 1) {
            throw IdempotencyConfigurationException.property(PREFIX + "batch-size", "it must be positive");
        }
        return new IdempotencyJdbcCleanup(store, clock.getIfUnique(Clock::systemUTC), interval, cleanup.getBatchSize());
    }
}
