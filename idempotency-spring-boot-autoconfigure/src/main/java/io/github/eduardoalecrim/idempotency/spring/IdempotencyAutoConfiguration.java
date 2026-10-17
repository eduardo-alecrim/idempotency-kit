// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import io.github.eduardoalecrim.idempotency.IdempotencyEngine;
import io.github.eduardoalecrim.idempotency.IdempotencyObserver;
import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * The engine and the default policy. The store comes from the application or from a store auto-configuration.
 *
 * <p>Record times come from the application's {@link Clock} bean when there is one, otherwise from the UTC system
 * clock.
 */
@AutoConfiguration(after = IdempotencyInMemoryStoreAutoConfiguration.class)
@ConditionalOnBooleanProperty(name = "idempotency.enabled", matchIfMissing = true)
@EnableConfigurationProperties(IdempotencyProperties.class)
public final class IdempotencyAutoConfiguration {

    /** Name of the default policy bean. */
    public static final String DEFAULT_POLICY_BEAN = "idempotencyDefaultPolicy";

    /** The policy of operations that do not override anything: the global properties. */
    @Bean(DEFAULT_POLICY_BEAN)
    @ConditionalOnMissingBean(name = DEFAULT_POLICY_BEAN)
    IdempotencyPolicy idempotencyDefaultPolicy(IdempotencyProperties properties) {
        PropertiesMapper.methods(properties);
        PropertiesMapper.checkClientIdentity(properties);
        return PropertiesMapper.defaultPolicy(properties);
    }

    /** The engine, on the single {@link IdempotencyStore} bean. */
    @Bean
    @ConditionalOnMissingBean
    IdempotencyEngine idempotencyEngine(
            IdempotencyProperties properties,
            IdempotencyStore store,
            ObjectProvider<IdempotencyObserver> observer,
            ObjectProvider<Clock> clock) {
        return IdempotencyEngine.builder()
                .store(store)
                .clock(clock.getIfUnique(Clock::systemUTC))
                .observer(observer.getIfUnique(() -> IdempotencyObserver.NOOP))
                .headerName(properties.getHeaderName())
                .keyParser(PropertiesMapper.keyParser(properties))
                .problemFactory(PropertiesMapper.problemFactory(properties))
                .onStoreFailure(properties.getStore().getOnFailure())
                .build();
    }
}
