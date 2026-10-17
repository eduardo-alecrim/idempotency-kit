// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.tck;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.internal.Hex;
import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.ClaimResult;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyRecord;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.RecordState;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse.Header;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link IdempotencyStore} must have. Extend it and implement {@link #createStore(Clock)}.
 *
 * <p>Each test uses its own random scope keys, so stores may share a table between tests. Record times have
 * millisecond precision, as the engine writes them. The number of rounds of the concurrency test is read from the
 * system property {@value #RACE_ITERATIONS_PROPERTY} (default 20).
 */
public abstract class IdempotencyStoreContract {

    /** System property with the number of rounds of the concurrency test. */
    public static final String RACE_ITERATIONS_PROPERTY = "idempotency.tck.race.iterations";

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The clock passed to {@link #createStore(Clock)}; tests advance it and pass its instants to the store. */
    protected final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00.123Z"));

    private IdempotencyStore store;

    /** Returns the store under test, ready for use. Called before each test. */
    protected abstract IdempotencyStore createStore(Clock clock);

    /** Releases what {@link #createStore(Clock)} opened. Called after each test; does nothing by default. */
    protected void destroyStore(IdempotencyStore store) {}

    @BeforeEach
    void openStore() {
        store = createStore(clock);
    }

    @AfterEach
    void closeStore() {
        destroyStore(store);
    }

    @Test
    @DisplayName("claim acquires an absent key and writes the in-progress record")
    void claimAcquiresAnAbsentKey() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());

        OwnerToken token = acquired(store.claim(claim));

        assertThat(store.find(claim.key(), now())).hasValue(claim.toRecord(token));
    }

    @Test
    @DisplayName("claim reports the live record and changes nothing")
    void claimReportsTheLiveRecord() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        OwnerToken token = acquired(store.claim(claim));
        clock.advance(Duration.ofSeconds(5));

        ClaimResult second = store.claim(claim(claim.key(), fingerprint(), now()));

        assertThat(second).isEqualTo(new ClaimResult.Existing(claim.toRecord(token)));
    }

    @Test
    @DisplayName("a lease is live until its last millisecond")
    void leaseIsLiveUntilItsEnd() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        store.claim(claim);
        clock.advance(LEASE);

        assertThat(store.claim(claim(claim.key(), fingerprint(), now()))).isInstanceOf(ClaimResult.Existing.class);
        assertThat(store.find(claim.key(), now())).isPresent();
    }

    @Test
    @DisplayName("FR-09: after the lease, a claim with any fingerprint takes over and the old owner cannot complete")
    void takesOverAnExpiredLease() {
        ClaimRequest first = claim(scopeKey(), fingerprint(), now());
        OwnerToken old = acquired(store.claim(first));
        clock.advance(LEASE.plusMillis(1));
        ClaimRequest takeover = claim(first.key(), fingerprint(), now());

        OwnerToken token = acquired(store.claim(takeover));

        assertThat(token).isNotEqualTo(old);
        assertThat(store.find(first.key(), now())).hasValue(takeover.toRecord(token));
        assertThat(store.complete(first.key(), old, response(), now(), now().plus(TTL)))
                .isFalse();
        assertThat(store.find(first.key(), now())).hasValue(takeover.toRecord(token));
        store.release(first.key(), old);
        assertThat(store.find(first.key(), now())).isPresent();
    }

    @Test
    @DisplayName("complete stores the response for the owner and drops the token and lease")
    void completeStoresTheResponse() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        OwnerToken token = acquired(store.claim(claim));
        clock.advance(Duration.ofSeconds(2));
        StoredResponse response = response();

        assertThat(store.complete(claim.key(), token, response, now(), now().plus(TTL)))
                .isTrue();

        IdempotencyRecord completed = store.find(claim.key(), now()).orElseThrow();
        assertThat(completed).isEqualTo(claim.toRecord(token).complete(response, now(), now().plus(TTL)));
        assertThat(completed.state()).isEqualTo(RecordState.COMPLETED);
        assertThat(completed.ownerToken()).isNull();
    }

    @Test
    @DisplayName("complete refuses another token, a completed record and a missing key")
    void completeRefusesOtherOwners() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        OwnerToken token = acquired(store.claim(claim));

        assertThat(store.complete(claim.key(), OwnerToken.random(), response(), now(), now().plus(TTL)))
                .isFalse();
        assertThat(store.find(claim.key(), now())).hasValue(claim.toRecord(token));
        assertThat(store.complete(claim.key(), token, response(), now(), now().plus(TTL)))
                .isTrue();
        assertThat(store.complete(claim.key(), token, response(), now(), now().plus(TTL)))
                .isFalse();
        assertThat(store.complete(scopeKey(), token, response(), now(), now().plus(TTL)))
                .isFalse();
    }

    @Test
    @DisplayName("responses read back unchanged: status, headers in order, body bytes")
    void responsesRoundTrip() {
        byte[] body = new byte[1024 * 1024];
        RANDOM.nextBytes(body);
        StoredResponse large = new StoredResponse(
                201,
                List.of(
                        new Header("Content-Type", "application/octet-stream"),
                        new Header("Cache-Control", "no-store"),
                        new Header("Cache-Control", "private"),
                        new Header("Content-Language", "pt-BR"),
                        new Header("ETag", "\"é-1\"")),
                body,
                false);

        assertThat(completeWith(large).response()).isEqualTo(large);
        assertThat(completeWith(new StoredResponse(204, List.of(), new byte[0], false))
                        .response())
                .isEqualTo(new StoredResponse(204, List.of(), new byte[0], false));
    }

    @Test
    @DisplayName("an omitted body reads back as omitted")
    void omittedBodiesRoundTrip() {
        StoredResponse omitted = new StoredResponse(200, List.of(new Header("Location", "/a")), new byte[0], true);

        assertThat(completeWith(omitted).response()).isEqualTo(omitted);
    }

    @Test
    @DisplayName("scope parts read back unchanged, including Unicode and long values")
    void scopePartsRoundTrip() {
        ScopeParts parts = new ScopeParts(
                "k".repeat(254) + "é", "ação-üñî-" + "c".repeat(200), "PATCH", "/a/" + "{id}/".repeat(100));
        ClaimRequest claim =
                new ClaimRequest(scopeKey(), parts, fingerprint(), now(), now().plus(LEASE), now().plus(TTL));

        OwnerToken token = acquired(store.claim(claim));

        assertThat(store.find(claim.key(), now()).orElseThrow().parts()).isEqualTo(parts);
        assertThat(store.find(claim.key(), now())).hasValue(claim.toRecord(token));
    }

    @Test
    @DisplayName("release deletes the owner's in-progress record only")
    void releaseDeletesOnlyTheOwnersRecord() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        OwnerToken token = acquired(store.claim(claim));

        store.release(claim.key(), OwnerToken.random());
        assertThat(store.find(claim.key(), now())).isPresent();

        store.release(claim.key(), token);
        assertThat(store.find(claim.key(), now())).isEmpty();
        assertThat(store.claim(claim(claim.key(), fingerprint(), now()))).isInstanceOf(ClaimResult.Acquired.class);
    }

    @Test
    @DisplayName("release keeps a completed record")
    void releaseKeepsCompletedRecords() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        OwnerToken token = acquired(store.claim(claim));
        store.complete(claim.key(), token, response(), now(), now().plus(TTL));

        store.release(claim.key(), token);

        assertThat(store.find(claim.key(), now())).isPresent();
    }

    @Test
    @DisplayName("ADR 0008: expired records are absent to find and claim")
    void expiredRecordsAreAbsent() {
        ClaimRequest inProgress = claim(scopeKey(), fingerprint(), now());
        store.claim(inProgress);
        ClaimRequest done = claim(scopeKey(), fingerprint(), now());
        store.complete(done.key(), acquired(store.claim(done)), response(), now(), now().plus(TTL));

        clock.advance(LEASE.plusMillis(1));
        assertThat(store.find(inProgress.key(), now())).isEmpty();
        assertThat(store.find(done.key(), now())).isPresent();

        clock.advance(TTL);
        assertThat(store.find(done.key(), now())).isEmpty();
        assertThat(store.claim(claim(done.key(), fingerprint(), now()))).isInstanceOf(ClaimResult.Acquired.class);
        assertThat(store.find(scopeKey(), now())).isEmpty();
    }

    @Test
    @DisplayName("FR-10: after the TTL, a claim with any fingerprint acquires the key")
    void acquiresAgainAfterTheTtl() {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        store.complete(claim.key(), acquired(store.claim(claim)), response(), now(), now().plus(TTL));
        clock.advance(TTL.plusMillis(1));
        ClaimRequest again = claim(claim.key(), fingerprint(), now());

        OwnerToken token = acquired(store.claim(again));

        assertThat(store.find(claim.key(), now())).hasValue(again.toRecord(token));
    }

    @Test
    @DisplayName("purgeExpired deletes expired records in batches and keeps live ones")
    void purgeDeletesExpiredRecordsInBatches() {
        List<ScopeKey> expired = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ClaimRequest claim =
                    new ClaimRequest(scopeKey(), parts(), fingerprint(), now(), now().plus(LEASE), now().plus(LEASE));
            store.claim(claim);
            expired.add(claim.key());
        }
        ClaimRequest live = claim(scopeKey(), fingerprint(), now());
        store.complete(live.key(), acquired(store.claim(live)), response(), now(), now().plus(TTL));
        clock.advance(LEASE.plusSeconds(1));

        int purged = 0;
        int batch;
        do {
            batch = store.purgeExpired(now(), 2);
            assertThat(batch).isBetween(0, 2);
            purged += batch;
        } while (batch == 2);

        assertThat(purged).isGreaterThanOrEqualTo(expired.size());
        assertThat(store.purgeExpired(now(), 2)).isZero();
        assertThat(store.claim(claim(live.key(), fingerprint(), now()))).isInstanceOf(ClaimResult.Existing.class);
    }

    @Test
    @DisplayName("concurrent claims on one scope key return exactly one Acquired")
    void concurrentClaimsAcquireOnce() throws Exception {
        int iterations = Integer.getInteger(RACE_ITERATIONS_PROPERTY, 20);
        int threads = 64;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < iterations; round++) {
                ScopeKey key = scopeKey();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<ClaimResult>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Instant now = now();
                    results.add(executor.submit(() -> {
                        start.await();
                        return store.claim(claim(key, fingerprint(), now));
                    }));
                }
                start.countDown();
                int acquired = 0;
                for (Future<ClaimResult> result : results) {
                    if (result.get() instanceof ClaimResult.Acquired) {
                        acquired++;
                    }
                }
                assertThat(acquired).as("round %d", round).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("health reports the store as up")
    void reportsHealth() {
        assertThat(store.health().up()).isTrue();
    }

    private IdempotencyRecord completeWith(StoredResponse response) {
        ClaimRequest claim = claim(scopeKey(), fingerprint(), now());
        OwnerToken token = acquired(store.claim(claim));
        assertThat(store.complete(claim.key(), token, response, now(), now().plus(TTL)))
                .isTrue();
        return store.find(claim.key(), now()).orElseThrow();
    }

    private Instant now() {
        return clock.instant();
    }

    private static ClaimRequest claim(ScopeKey key, Fingerprint fingerprint, Instant now) {
        return new ClaimRequest(key, parts(), fingerprint, now, now.plus(LEASE), now.plus(TTL));
    }

    private static ScopeParts parts() {
        return new ScopeParts("4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12", "alice", "POST", "/orders");
    }

    private static StoredResponse response() {
        return new StoredResponse(
                201,
                List.of(new Header("Content-Type", "application/json"), new Header("Location", "/orders/1")),
                "{\"id\":1}".getBytes(StandardCharsets.UTF_8),
                false);
    }

    private static ScopeKey scopeKey() {
        return new ScopeKey(randomHex());
    }

    private static Fingerprint fingerprint() {
        return new Fingerprint(randomHex());
    }

    private static String randomHex() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Hex.encode(bytes);
    }

    private static OwnerToken acquired(ClaimResult result) {
        assertThat(result).isInstanceOf(ClaimResult.Acquired.class);
        return ((ClaimResult.Acquired) result).token();
    }
}
