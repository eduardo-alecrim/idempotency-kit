// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.ClaimResult;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyRecord;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.RecordState;
import io.github.eduardoalecrim.idempotency.spi.RecordSummary;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-17T10:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration TTL = Duration.ofHours(24);
    private static final Fingerprint FINGERPRINT = new Fingerprint("f".repeat(64));
    private static final StoredResponse RESPONSE = new StoredResponse(201, List.of(), new byte[] {1}, false);

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();

    @Test
    void claimAcquiresANewKeyAndReportsTheRecordAfterwards() {
        ClaimResult first = store.claim(claim(key(1), NOW));
        ClaimResult second = store.claim(claim(key(1), NOW.plusSeconds(1)));

        assertThat(first).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(second).isInstanceOfSatisfying(ClaimResult.Existing.class, existing -> {
            assertThat(existing.record().state()).isEqualTo(RecordState.IN_PROGRESS);
            assertThat(existing.record().ownerToken()).isEqualTo(token(first));
        });
    }

    @Test
    void completeStoresTheResponseOnlyForTheOwner() {
        OwnerToken token = token(store.claim(claim(key(1), NOW)));

        assertThat(store.complete(key(1), OwnerToken.random(), RESPONSE, NOW, NOW.plus(TTL)))
                .isFalse();
        assertThat(store.complete(key(1), token, RESPONSE, NOW.plusSeconds(1), NOW.plus(TTL)))
                .isTrue();
        assertThat(store.complete(key(1), token, RESPONSE, NOW.plusSeconds(2), NOW.plus(TTL)))
                .isFalse();
        assertThat(store.complete(key(2), token, RESPONSE, NOW, NOW.plus(TTL))).isFalse();

        IdempotencyRecord record = store.find(key(1), NOW.plusSeconds(3)).orElseThrow();
        assertThat(record.state()).isEqualTo(RecordState.COMPLETED);
        assertThat(record.response()).isEqualTo(RESPONSE);
        assertThat(record.completedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void releaseDeletesOnlyTheOwnersInProgressRecord() {
        OwnerToken token = token(store.claim(claim(key(1), NOW)));

        store.release(key(1), OwnerToken.random());
        assertThat(store.find(key(1), NOW)).isPresent();

        store.release(key(1), token);
        assertThat(store.find(key(1), NOW)).isEmpty();
        assertThat(store.claim(claim(key(1), NOW))).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    void releaseKeepsACompletedRecord() {
        OwnerToken token = token(store.claim(claim(key(1), NOW)));
        store.complete(key(1), token, RESPONSE, NOW, NOW.plus(TTL));

        store.release(key(1), token);

        assertThat(store.find(key(1), NOW)).isPresent();
    }

    @Test
    @DisplayName("FR-09: a claim takes over an expired lease and the old owner can no longer complete")
    void takesOverAnExpiredLease() {
        OwnerToken first = token(store.claim(claim(key(1), NOW)));
        Instant afterLease = NOW.plus(LEASE).plusMillis(1);

        assertThat(store.find(key(1), afterLease)).isEmpty();
        OwnerToken second = token(store.claim(claim(key(1), afterLease)));

        assertThat(second).isNotEqualTo(first);
        assertThat(store.complete(key(1), first, RESPONSE, afterLease, afterLease.plus(TTL)))
                .isFalse();
        assertThat(store.complete(key(1), second, RESPONSE, afterLease, afterLease.plus(TTL)))
                .isTrue();
    }

    @Test
    void keepsTheLeaseUntilItsLastInstant() {
        store.claim(claim(key(1), NOW));

        assertThat(store.claim(claim(key(1), NOW.plus(LEASE)))).isInstanceOf(ClaimResult.Existing.class);
    }

    @Test
    @DisplayName("FR-10: after the TTL a claim acquires the key again")
    void acquiresAgainAfterTheTtl() {
        OwnerToken token = token(store.claim(claim(key(1), NOW)));
        store.complete(key(1), token, RESPONSE, NOW, NOW.plus(TTL));

        assertThat(store.claim(claim(key(1), NOW.plus(TTL)))).isInstanceOf(ClaimResult.Existing.class);
        assertThat(store.find(key(1), NOW.plus(TTL).plusMillis(1))).isEmpty();
        assertThat(store.claim(claim(key(1), NOW.plus(TTL).plusMillis(1)))).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    void purgeDeletesExpiredRecordsInBatches() {
        for (int i = 0; i < 5; i++) {
            OwnerToken token = token(store.claim(claim(key(i), NOW)));
            store.complete(key(i), token, RESPONSE, NOW, NOW.plusSeconds(60));
        }
        store.claim(claim(key(9), NOW));
        Instant later = NOW.plusSeconds(61);

        assertThat(store.purgeExpired(later, 3)).isEqualTo(3);
        assertThat(store.purgeExpired(later, 3)).isEqualTo(2);
        assertThat(store.purgeExpired(later, 3)).isZero();
        assertThat(store.health().detail()).isEqualTo("in-memory, 1 of 10000 records");
    }

    @Test
    @DisplayName("FR-23: the store is bounded and never evicts live records")
    void rejectsClaimsWhenFull() {
        InMemoryIdempotencyStore small = new InMemoryIdempotencyStore(2);
        OwnerToken token = token(small.claim(claim(key(1), NOW)));
        small.claim(claim(key(2), NOW));

        assertThatThrownBy(() -> small.claim(claim(key(3), NOW)))
                .isInstanceOf(IdempotencyStoreException.class)
                .hasMessageContaining("2 live records");
        assertThat(small.claim(claim(key(1), NOW))).isInstanceOf(ClaimResult.Existing.class);

        small.release(key(1), token);
        assertThat(small.claim(claim(key(3), NOW))).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    @DisplayName("FR-23: a full store makes room by dropping expired records")
    void dropsExpiredRecordsWhenFull() {
        InMemoryIdempotencyStore small = new InMemoryIdempotencyStore(2);
        small.claim(claim(key(1), NOW));
        small.claim(claim(key(2), NOW));

        assertThat(small.claim(claim(key(3), NOW.plus(LEASE).plusMillis(1)))).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(small.health().detail()).isEqualTo("in-memory, 1 of 2 records");
    }

    @RepeatedTest(20)
    @DisplayName("FR-23: concurrent claims on one scope key return exactly one Acquired")
    void concurrentClaimsAcquireOnce() throws Exception {
        int threads = 64;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ClaimResult>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return store.claim(claim(key(1), NOW));
                }));
            }
            start.countDown();
            int acquired = 0;
            for (Future<ClaimResult> result : results) {
                if (result.get() instanceof ClaimResult.Acquired) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("FR-23: the store warns that it is not shared between instances")
    void warnsOnCreation() {
        Logger logger = Logger.getLogger(InMemoryIdempotencyStore.class.getName());
        List<LogRecord> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            new InMemoryIdempotencyStore(1);
        } finally {
            logger.removeHandler(handler);
        }

        assertThat(logged).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).contains("not shared between instances");
        });
    }

    @Test
    void listsRecentRecordsNewestFirst() {
        store.claim(claim(key(1), NOW));
        store.claim(claim(key(2), NOW.plusSeconds(2)));
        store.claim(claim(key(3), NOW.plusSeconds(1)));

        assertThat(store.recent(2)).extracting(RecordSummary::key).containsExactly(key(2), key(3));
    }

    @Test
    void reportsHealthAndRejectsInvalidBounds() {
        assertThat(store.health().up()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> new InMemoryIdempotencyStore(0));
    }

    private static ClaimRequest claim(ScopeKey key, Instant now) {
        return new ClaimRequest(
                key, new ScopeParts("k", "", "POST", "/orders"), FINGERPRINT, now, now.plus(LEASE), now.plus(TTL));
    }

    private static ScopeKey key(int i) {
        return new ScopeKey(String.format("%064x", i));
    }

    private static OwnerToken token(ClaimResult result) {
        assertThat(result).isInstanceOf(ClaimResult.Acquired.class);
        return ((ClaimResult.Acquired) result).token();
    }
}
