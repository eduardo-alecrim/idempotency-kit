// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.Decision;
import io.github.eduardoalecrim.idempotency.IdempotencyEngine;
import io.github.eduardoalecrim.idempotency.IdempotencyObserver;
import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.IncomingRequest;
import io.github.eduardoalecrim.idempotency.Outcome;
import io.github.eduardoalecrim.idempotency.OversizedPolicy;
import io.github.eduardoalecrim.idempotency.RouteRef;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.RecordSummary;
import io.github.eduardoalecrim.idempotency.store.memory.InMemoryIdempotencyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class IdempotencyAutoConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    IdempotencyInMemoryStoreAutoConfiguration.class, IdempotencyAutoConfiguration.class));

    @Test
    @DisplayName("FR-28: with no configuration, the engine runs on the in-memory store with the documented defaults")
    void configuresDefaults() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(IdempotencyEngine.class);
            assertThat(context).hasSingleBean(InMemoryIdempotencyStore.class);
            IdempotencyPolicy policy = defaultPolicy(context);
            assertThat(policy.ttl()).isEqualTo(Duration.ofHours(24));
            assertThat(policy.lease()).isEqualTo(Duration.ofSeconds(30));
            assertThat(policy.maxRequestBytes()).isEqualTo(1024 * 1024);
            assertThat(policy.replayHeaders()).isEqualTo(IdempotencyPolicy.DEFAULT_REPLAY_HEADERS);
            assertThat(context.getBean(IdempotencyEngine.class).headerName()).isEqualTo("Idempotency-Key");
        });
    }

    @Test
    void disabledConfiguresNothing() {
        runner.withPropertyValues("idempotency.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(IdempotencyEngine.class);
            assertThat(context).doesNotHaveBean(IdempotencyStore.class);
        });
    }

    @Test
    void anApplicationStoreReplacesTheInMemoryStore() {
        runner.withUserConfiguration(CustomStore.class).run(context -> {
            assertThat(context).hasSingleBean(IdempotencyStore.class);
            assertThat(context).doesNotHaveBean("idempotencyStore");
            assertThat(context.getBean(IdempotencyStore.class)).isSameAs(CustomStore.STORE);
        });
    }

    @Test
    void otherStoreTypesDoNotFallBackToMemory() {
        runner.withPropertyValues("idempotency.store.type=jdbc")
                .run(context -> assertThat(context)
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("No qualifying bean of type '" + IdempotencyStore.class.getName() + "'"));
        runner.withPropertyValues("idempotency.store.type=in-memory")
                .run(context -> assertThat(context).hasSingleBean(InMemoryIdempotencyStore.class));
    }

    @Test
    void mapsPropertiesToTheDefaultPolicy() {
        runner.withPropertyValues(
                        "idempotency.default-ttl=2h",
                        "idempotency.lease=90s",
                        "idempotency.fingerprint.headers=X-Tenant",
                        "idempotency.request.max-body-size=64KB",
                        "idempotency.response.max-body-size=2MB",
                        "idempotency.response.oversized=release",
                        "idempotency.response.store-statuses=2xx",
                        "idempotency.response.replay-headers=Location,ETag",
                        "idempotency.header-name=X-Idempotency-Key")
                .run(context -> {
                    IdempotencyPolicy policy = defaultPolicy(context);
                    assertThat(policy.ttl()).isEqualTo(Duration.ofHours(2));
                    assertThat(policy.lease()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(policy.fingerprintHeaders()).containsExactly("x-tenant");
                    assertThat(policy.maxRequestBytes()).isEqualTo(64 * 1024);
                    assertThat(policy.maxResponseBytes()).isEqualTo(2 * 1024 * 1024);
                    assertThat(policy.oversized()).isEqualTo(OversizedPolicy.RELEASE);
                    assertThat(policy.statusPolicy().isStorable(422)).isFalse();
                    assertThat(policy.replayHeaders()).containsExactlyInAnyOrder("location", "etag");
                    assertThat(context.getBean(IdempotencyEngine.class).headerName())
                            .isEqualTo("X-Idempotency-Key");
                });
    }

    @Test
    void usesTheApplicationClockAndObserver() {
        runner.withUserConfiguration(ClockAndObserver.class).run(context -> {
            IdempotencyEngine engine = context.getBean(IdempotencyEngine.class);

            Decision decision = engine.begin(new Request("\"k1\""), defaultPolicy(context));

            assertThat(decision).isInstanceOf(Decision.Proceed.class);
            List<RecordSummary> records =
                    context.getBean(InMemoryIdempotencyStore.class).recent(1);
            assertThat(records)
                    .singleElement()
                    .extracting(RecordSummary::createdAt)
                    .isEqualTo(NOW);
            assertThat(context.getBean(ClockAndObserver.class).outcomes).isEmpty();
            engine.begin(new Request(null), defaultPolicy(context));
            assertThat(context.getBean(ClockAndObserver.class).outcomes).containsExactly(Outcome.MISSING_KEY);
        });
    }

    @Test
    @DisplayName("a lease that is not shorter than the TTL fails startup with a report naming the property")
    void rejectsLeaseNotShorterThanTtl() {
        runner.withPropertyValues("idempotency.default-ttl=30s").run(context -> {
            FailureAnalysis analysis = analyze(context);
            assertThat(analysis.getDescription())
                    .isEqualTo("Invalid value of 'idempotency.lease': the lease (30s) must be shorter than"
                            + " idempotency.default-ttl (30s)");
            assertThat(analysis.getAction()).isEqualTo("Update 'idempotency.lease' in the configuration.");
        });
    }

    @Test
    @DisplayName("FR-37: a denied replay header fails startup")
    void rejectsDeniedReplayHeaders() {
        runner.withPropertyValues("idempotency.response.replay-headers=Content-Type,Set-Cookie")
                .run(context -> assertThat(analyze(context).getDescription())
                        .contains("idempotency.response.replay-headers", "Set-Cookie"));
    }

    @Test
    void rejectsOtherInvalidValues() {
        expectInvalid("idempotency.methods", "idempotency.methods=POST,GET");
        expectInvalid("idempotency.client-identity.header", "idempotency.client-identity.source=header");
        expectInvalid("idempotency.response.store-statuses", "idempotency.response.store-statuses=5xx");
        expectInvalid("idempotency.key.max-length", "idempotency.key.max-length=0");
        expectInvalid("idempotency.problems.base-uri", "idempotency.problems.base-uri=https://example.com/p");
        expectInvalid("idempotency.store.in-memory.max-entries", "idempotency.store.in-memory.max-entries=0");
        expectInvalid("idempotency.request.max-body-size", "idempotency.request.max-body-size=4GB");
    }

    private void expectInvalid(String property, String value) {
        runner.withPropertyValues(value)
                .run(context -> assertThat(analyze(context).getDescription())
                        .as(value)
                        .startsWith("Invalid value of '" + property + "'"));
    }

    private static FailureAnalysis analyze(AssertableApplicationContext context) {
        assertThat(context).hasFailed();
        Throwable failure = Objects.requireNonNull(context.getStartupFailure());
        return Objects.requireNonNull(new IdempotencyFailureAnalyzer().analyze(failure), "no analysis");
    }

    private static IdempotencyPolicy defaultPolicy(AssertableApplicationContext context) {
        return context.getBean(IdempotencyAutoConfiguration.DEFAULT_POLICY_BEAN, IdempotencyPolicy.class);
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomStore {

        static final IdempotencyStore STORE = new InMemoryIdempotencyStore(5);

        @Bean
        IdempotencyStore customStore() {
            return STORE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ClockAndObserver implements IdempotencyObserver {

        final List<Outcome> outcomes = new CopyOnWriteArrayList<>();

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Override
        public void onOutcome(Outcome outcome, RouteRef route, Duration elapsed) {
            outcomes.add(outcome);
        }
    }

    private record Request(@Nullable String key) implements IncomingRequest {

        @Override
        public String method() {
            return "POST";
        }

        @Override
        public String path() {
            return "/orders";
        }

        @Override
        public String routeTemplate() {
            return "/orders";
        }

        @Override
        public List<String> headerValues(String name) {
            return key != null && name.toLowerCase(Locale.ROOT).equals("idempotency-key") ? List.of(key) : List.of();
        }

        @Override
        public @Nullable String contentType() {
            return null;
        }

        @Override
        public Optional<String> clientIdentity() {
            return Optional.empty();
        }

        @Override
        public byte[] body(int maxBytes) {
            return new byte[0];
        }
    }
}
