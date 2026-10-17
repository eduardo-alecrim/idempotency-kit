// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.tck;

import io.github.eduardoalecrim.idempotency.Idempotent;
import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.ClaimResult;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyRecord;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.StoreHealth;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import io.github.eduardoalecrim.idempotency.spring.tck.HttpBehaviourContract.StoreFailure;
import io.github.eduardoalecrim.idempotency.store.memory.InMemoryIdempotencyStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** The {@code /tck/*} endpoints of SPEC §9, with a controllable clock and store. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(TckApplication.TckController.class)
class TckApplication {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    @Bean
    TestClock clock() {
        return new TestClock(Instant.parse("2026-10-17T10:00:00Z"));
    }

    @Bean
    ControllableStore store() {
        return new ControllableStore(new InMemoryIdempotencyStore());
    }

    @RestController
    static class TckController {

        final AtomicInteger executions = new AtomicInteger();
        final AtomicInteger blocked = new AtomicInteger();
        private final AtomicReference<CountDownLatch> gate = new AtomicReference<>(new CountDownLatch(1));

        @Idempotent
        @PostMapping("/tck/orders")
        ResponseEntity<String> orders() {
            int id = executions.incrementAndGet();
            return ResponseEntity.created(URI.create("/tck/orders/" + id)).body("{\"execution\":" + id + "}");
        }

        @Idempotent
        @PostMapping("/tck/slow")
        ResponseEntity<String> slow() throws InterruptedException {
            int id = executions.incrementAndGet();
            blocked.incrementAndGet();
            try {
                if (!gate.get().await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("/tck/slow was never released");
                }
            } finally {
                blocked.decrementAndGet();
            }
            return ResponseEntity.created(URI.create("/tck/orders/" + id)).body("{\"execution\":" + id + "}");
        }

        @Idempotent
        @PostMapping("/tck/boom")
        String boom() {
            executions.incrementAndGet();
            throw new IllegalStateException("boom");
        }

        @Idempotent
        @PostMapping("/tck/unavailable")
        ResponseEntity<String> unavailable() {
            executions.incrementAndGet();
            return ResponseEntity.status(503).body("{}");
        }

        @Idempotent
        @PostMapping("/tck/rejected")
        ResponseEntity<String> rejected() {
            executions.incrementAndGet();
            return ResponseEntity.badRequest().body("{\"error\":\"rejected\"}");
        }

        @Idempotent
        @PostMapping("/tck/no-content")
        ResponseEntity<Void> noContent() {
            executions.incrementAndGet();
            return ResponseEntity.noContent().build();
        }

        @Idempotent
        @PostMapping("/tck/big")
        String big() {
            executions.incrementAndGet();
            return "x".repeat(4096);
        }

        @Idempotent(required = false)
        @PostMapping("/tck/optional")
        String optional() {
            return "{\"execution\":" + executions.incrementAndGet() + "}";
        }

        @GetMapping("/tck/executions")
        String executions() {
            return Integer.toString(executions.get());
        }

        void releaseSlow() {
            gate.getAndSet(new CountDownLatch(1)).countDown();
        }
    }

    /** A clock that moves only when a test advances it. */
    static final class TestClock extends Clock {

        private volatile Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Delegates to a store, failing as the test asks. */
    static final class ControllableStore implements IdempotencyStore {

        private final IdempotencyStore delegate;
        volatile StoreFailure failure = StoreFailure.NONE;

        ControllableStore(IdempotencyStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ClaimResult claim(ClaimRequest request) {
            failIf(failure == StoreFailure.EVERY_CALL);
            return delegate.claim(request);
        }

        @Override
        public boolean complete(
                ScopeKey key, OwnerToken token, StoredResponse response, Instant completedAt, Instant expiresAt) {
            failIf(failure != StoreFailure.NONE);
            return delegate.complete(key, token, response, completedAt, expiresAt);
        }

        @Override
        public void release(ScopeKey key, OwnerToken token) {
            failIf(failure != StoreFailure.NONE);
            delegate.release(key, token);
        }

        @Override
        public Optional<IdempotencyRecord> find(ScopeKey key, Instant now) {
            failIf(failure == StoreFailure.EVERY_CALL);
            return delegate.find(key, now);
        }

        @Override
        public int purgeExpired(Instant now, int batchSize) {
            return delegate.purgeExpired(now, batchSize);
        }

        @Override
        public StoreHealth health() {
            return delegate.health();
        }

        private static void failIf(boolean fail) {
            if (fail) {
                throw new IdempotencyStoreException("store failure requested by the test");
            }
        }
    }
}
