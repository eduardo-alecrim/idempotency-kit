// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.eduardoalecrim.idempotency.IdempotencyObserver;
import io.github.eduardoalecrim.idempotency.Idempotent;
import io.github.eduardoalecrim.idempotency.RouteRef;
import io.github.eduardoalecrim.idempotency.tck.IdempotencyStoreContract;
import io.github.eduardoalecrim.idempotency.tck.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * FR-09 and FR-11 across two application instances that share one PostgreSQL store and one clock. The number of race
 * rounds is read from {@value IdempotencyStoreContract#RACE_ITERATIONS_PROPERTY} (default 20).
 */
@DisplayName("Two instances on one JDBC store")
class TwoInstanceTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");
    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
    private static final AtomicInteger LEASES_LOST = new AtomicInteger();
    private static final int REQUESTS = 64;

    private static final List<ConfigurableApplicationContext> INSTANCES = new ArrayList<>();
    private static final List<URI> BASE_URIS = new ArrayList<>();

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    static void startInstances() {
        POSTGRES.start();
        for (int i = 0; i < 2; i++) {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(App.class)
                    .properties("server.port=0", "idempotency.store.jdbc.initialize-schema=always")
                    .run();
            INSTANCES.add(context);
            BASE_URIS.add(URI.create(
                    "http://localhost:" + context.getEnvironment().getRequiredProperty("local.server.port")));
        }
    }

    @AfterAll
    static void stopInstances() {
        INSTANCES.forEach(ConfigurableApplicationContext::close);
        POSTGRES.stop();
    }

    @Test
    @DisplayName("FR-11: 64 concurrent requests on two instances run the handler once; the rest get it or 409")
    void concurrentRequestsRunOnce() throws Exception {
        int rounds = Integer.getInteger(IdempotencyStoreContract.RACE_ITERATIONS_PROPERTY, 20);
        int inProgress = 0;
        for (int round = 0; round < rounds; round++) {
            String key = UUID.randomUUID().toString();
            List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
            for (int i = 0; i < REQUESTS; i++) {
                responses.add(http.sendAsync(
                        post(BASE_URIS.get(i % 2), "/orders", key), HttpResponse.BodyHandlers.ofString()));
            }
            CompletableFuture.allOf(responses.toArray(CompletableFuture[]::new)).get(30, TimeUnit.SECONDS);

            assertThat(Orders.EXECUTIONS.get(key))
                    .as("executions in round %d", round)
                    .hasValue(1);
            @Nullable String original = null;
            for (CompletableFuture<HttpResponse<String>> future : responses) {
                HttpResponse<String> response = future.get();
                if (response.statusCode() == 201) {
                    original = original == null ? response.body() : original;
                    assertThat(response.body())
                            .as("replayed body in round %d", round)
                            .isEqualTo(original);
                } else {
                    assertThat(response.statusCode())
                            .as("status in round %d", round)
                            .isEqualTo(409);
                    assertThat(response.body()).contains("request-in-progress");
                    inProgress++;
                }
            }
            assertThat(original).as("original response in round %d", round).isNotNull();
        }
        // The requests did overlap: some found the first one still running.
        assertThat(inProgress).isPositive();
    }

    @Test
    @DisplayName("FR-09: after the lease, the other instance takes over and the old owner's completion is lost")
    void expiredLeaseIsTakenOver() throws Exception {
        String key = UUID.randomUUID().toString();
        int lost = LEASES_LOST.get();
        Orders.Slow.ENTERED.set(new CountDownLatch(1));
        Orders.Slow.RELEASE.set(new CountDownLatch(1));

        CompletableFuture<HttpResponse<String>> first =
                http.sendAsync(post(BASE_URIS.get(0), "/slow", key), HttpResponse.BodyHandlers.ofString());
        assertThat(Objects.requireNonNull(Orders.Slow.ENTERED.get()).await(10, TimeUnit.SECONDS))
                .isTrue();
        CLOCK.advance(Duration.ofSeconds(31));
        HttpResponse<String> takeover =
                http.send(post(BASE_URIS.get(1), "/slow", key), HttpResponse.BodyHandlers.ofString());
        Objects.requireNonNull(Orders.Slow.RELEASE.get()).countDown();
        HttpResponse<String> late = first.get(10, TimeUnit.SECONDS);
        HttpResponse<String> replay =
                http.send(post(BASE_URIS.get(0), "/slow", key), HttpResponse.BodyHandlers.ofString());

        assertThat(Orders.EXECUTIONS.get(key)).hasValue(2);
        assertThat(takeover.statusCode()).isEqualTo(201);
        assertThat(late.statusCode()).isEqualTo(201);
        assertThat(late.body()).isNotEqualTo(takeover.body());
        assertThat(LEASES_LOST.get()).isEqualTo(lost + 1);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.body()).isEqualTo(takeover.body());
    }

    private static HttpRequest post(URI base, String path, String key) {
        return HttpRequest.newBuilder(base.resolve(path))
                .header("Idempotency-Key", "\"" + key + "\"")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"book\"}"))
                .build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(Orders.class)
    static class App {

        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(POSTGRES.getJdbcUrl());
            config.setUsername(POSTGRES.getUsername());
            config.setPassword(POSTGRES.getPassword());
            config.setMaximumPoolSize(16);
            return new HikariDataSource(config);
        }

        @Bean
        Clock clock() {
            return CLOCK;
        }

        @Bean
        IdempotencyObserver observer() {
            return new IdempotencyObserver() {
                @Override
                public void onLeaseLost(RouteRef route) {
                    LEASES_LOST.incrementAndGet();
                }
            };
        }

        @Bean
        SecurityFilterChain security(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                    .build();
        }
    }

    @RestController
    static class Orders {

        /** Handler executions per idempotency key, across both instances. */
        static final Map<String, AtomicInteger> EXECUTIONS = new ConcurrentHashMap<>();

        private static final AtomicInteger IDS = new AtomicInteger();

        @Idempotent
        @PostMapping("/orders")
        ResponseEntity<String> create(@RequestHeader("Idempotency-Key") String key) throws InterruptedException {
            EXECUTIONS
                    .computeIfAbsent(key.replace("\"", ""), k -> new AtomicInteger())
                    .incrementAndGet();
            // Keeps the request in progress while the others arrive.
            Thread.sleep(20);
            return created();
        }

        @Idempotent
        @PostMapping("/slow")
        ResponseEntity<String> slow(@RequestHeader("Idempotency-Key") String key) throws InterruptedException {
            EXECUTIONS
                    .computeIfAbsent(key.replace("\"", ""), k -> new AtomicInteger())
                    .incrementAndGet();
            CountDownLatch entered = Slow.ENTERED.getAndSet(null);
            if (entered != null) {
                entered.countDown();
                Objects.requireNonNull(Slow.RELEASE.get()).await(10, TimeUnit.SECONDS);
            }
            return created();
        }

        private static ResponseEntity<String> created() {
            return ResponseEntity.status(201)
                    .header("Content-Type", "application/json")
                    .body("{\"id\":" + IDS.incrementAndGet() + "}");
        }

        /** Makes the first {@code /slow} execution wait until the test lets it finish. */
        static final class Slow {
            static final AtomicReference<@Nullable CountDownLatch> ENTERED = new AtomicReference<>();
            static final AtomicReference<@Nullable CountDownLatch> RELEASE = new AtomicReference<>();

            private Slow() {}
        }
    }
}
