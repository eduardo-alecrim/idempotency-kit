// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.Idempotent.Scope;
import io.github.eduardoalecrim.idempotency.problem.ProblemType;
import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.ClaimResult;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyRecord;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStoreException;
import io.github.eduardoalecrim.idempotency.spi.OwnerToken;
import io.github.eduardoalecrim.idempotency.spi.RecordState;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.StoreHealth;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import io.github.eduardoalecrim.idempotency.store.memory.InMemoryIdempotencyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdempotencyEngineTest {

    private static final String KEY = "4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12";
    private static final String ORDER = "{\"item\":\"book\",\"quantity\":1}";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-17T10:00:00Z"));
    private final InMemoryIdempotencyStore memory = new InMemoryIdempotencyStore();
    private final FlakyStore store = new FlakyStore(memory);
    private final RecordingObserver observer = new RecordingObserver();
    private final IdempotencyEngine engine = engine(StoreFailurePolicy.FAIL_CLOSED);
    private final IdempotencyPolicy policy = IdempotencyPolicy.builder().build();

    @Test
    @DisplayName("FR-03: a required operation without a key answers 400 missing-key and creates no record")
    void rejectsMissingKey() {
        Decision decision = engine.begin(TestRequest.post("/orders").json(ORDER), policy);

        assertProblem(decision, ProblemType.MISSING_KEY);
        assertThat(((Decision.Reject) decision).problem().idempotencyKey()).isNull();
        assertThat(memory.recent(10)).isEmpty();
        assertThat(observer.outcomes).containsExactly(Outcome.MISSING_KEY);
    }

    @Test
    @DisplayName("FR-03: an optional operation without a key bypasses and creates no record")
    void bypassesOptionalOperationWithoutKey() {
        IdempotencyPolicy optional = policy.toBuilder().required(false).build();

        Decision decision = engine.begin(TestRequest.post("/orders").json(ORDER), optional);

        assertThat(decision).isEqualTo(new Decision.Bypass(BypassReason.NO_KEY));
        assertThat(memory.recent(10)).isEmpty();
        assertThat(observer.outcomes).containsExactly(Outcome.NO_KEY);
    }

    @Test
    @DisplayName("FR-01: a malformed key answers 400 invalid-key and never reaches the store")
    void rejectsMalformedKey() {
        Decision decision = engine.begin(TestRequest.post("/orders").header("Idempotency-Key", KEY), policy);

        assertProblem(decision, ProblemType.INVALID_KEY);
        assertThat(observer.storeCalls).isEmpty();
    }

    @Test
    @DisplayName("FR-01: repeated key headers are malformed")
    void rejectsRepeatedKeyHeaders() {
        Decision decision = engine.begin(TestRequest.post("/orders").key("a").key("b"), policy);

        assertProblem(decision, ProblemType.INVALID_KEY);
    }

    @Test
    @DisplayName("FR-04: a new key claims the record, and a storable response completes it")
    void executesAndCompletes() {
        Decision.Proceed proceed = proceed(engine.begin(order(), policy));
        engine.finish(proceed, response(201, ORDER));

        IdempotencyRecord record = memory.find(proceed.key(), clock.instant()).orElseThrow();
        assertThat(record.state()).isEqualTo(RecordState.COMPLETED);
        assertThat(record.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
        assertThat(observer.outcomes).containsExactly(Outcome.EXECUTED);
        assertThat(observer.storeCalls).containsExactly("CLAIM", "COMPLETE");
    }

    @Test
    @DisplayName("FR-05: a retry with the same request replays status, body and allowlisted headers")
    void replaysTheStoredResponse() {
        engine.finish(proceed(engine.begin(order(), policy)), response(201, ORDER));

        Decision decision = engine.begin(order(), policy);

        assertThat(decision).isInstanceOfSatisfying(Decision.Replay.class, replay -> {
            StoredResponse stored = replay.response();
            assertThat(stored.status()).isEqualTo(201);
            assertThat(stored.body()).isEqualTo(ORDER.getBytes(UTF_8));
            assertThat(stored.headers())
                    .containsExactly(
                            new StoredResponse.Header("Content-Type", "application/json"),
                            new StoredResponse.Header("Location", "/orders/1"));
        });
        assertThat(observer.outcomes).containsExactly(Outcome.EXECUTED, Outcome.REPLAYED);
    }

    @Test
    @DisplayName("FR-05: JSON formatting does not prevent a replay")
    void replaysReformattedJson() {
        engine.finish(proceed(engine.begin(order(), policy)), response(201, ORDER));

        Decision decision = engine.begin(
                TestRequest.post("/orders").key(KEY).json("{ \"quantity\": 1.0, \"item\": \"book\" }"), policy);

        assertThat(decision).isInstanceOf(Decision.Replay.class);
    }

    @Test
    @DisplayName("FR-06: a different request with a completed key answers 422 and leaves the record unchanged")
    void rejectsKeyReuseAfterCompletion() {
        Decision.Proceed proceed = proceed(engine.begin(order(), policy));
        engine.finish(proceed, response(201, ORDER));
        IdempotencyRecord before = memory.find(proceed.key(), clock.instant()).orElseThrow();

        Decision decision = engine.begin(TestRequest.post("/orders").key(KEY).json("{\"item\":\"pen\"}"), policy);

        assertProblem(decision, ProblemType.KEY_REUSE);
        assertThat(((Decision.Reject) decision).problem().idempotencyKey()).isEqualTo(KEY);
        assertThat(memory.find(proceed.key(), clock.instant())).hasValue(before);
    }

    @Test
    @DisplayName("FR-06: a different request with an in-progress key answers 422")
    void rejectsKeyReuseInProgress() {
        engine.begin(order(), policy);

        Decision decision = engine.begin(TestRequest.post("/orders").key(KEY).json("{\"item\":\"pen\"}"), policy);

        assertProblem(decision, ProblemType.KEY_REUSE);
    }

    @Test
    @DisplayName("FR-07: a retry while the lease runs answers 409 with Retry-After rounded up")
    void rejectsRequestInProgress() {
        Decision.Proceed first = proceed(engine.begin(order(), policy));
        clock.advance(Duration.ofMillis(17_700));

        Decision decision = engine.begin(order(), policy);

        assertProblem(decision, ProblemType.REQUEST_IN_PROGRESS);
        assertThat(((Decision.Reject) decision).headers()).containsEntry("Retry-After", "13");

        engine.finish(first, response(201, ORDER));
        assertThat(engine.begin(order(), policy)).isInstanceOf(Decision.Replay.class);
    }

    @Test
    void retryAfterIsWholeSecondsAndAtLeastOne() {
        Instant now = Instant.EPOCH;

        assertThat(IdempotencyEngine.retryAfterSeconds(now, now.plusMillis(200)))
                .isEqualTo(1);
        assertThat(IdempotencyEngine.retryAfterSeconds(now, now.plusMillis(2000)))
                .isEqualTo(2);
        assertThat(IdempotencyEngine.retryAfterSeconds(now, now.plusMillis(2001)))
                .isEqualTo(3);
        assertThat(IdempotencyEngine.retryAfterSeconds(now, now)).isEqualTo(1);
    }

    @Test
    @DisplayName("FR-08: a 503 releases the record, so a retry runs the handler again")
    void releasesTransientResponses() {
        Decision.Proceed proceed = proceed(engine.begin(order(), policy));
        engine.finish(proceed, response(503, "{}"));

        assertThat(memory.find(proceed.key(), clock.instant())).isEmpty();
        assertThat(engine.begin(order(), policy)).isInstanceOf(Decision.Proceed.class);
        assertThat(observer.outcomes).startsWith(Outcome.RELEASED);
    }

    @Test
    @DisplayName("FR-08: a handler that throws releases the record")
    void releasesOnException() {
        Decision.Proceed proceed = proceed(engine.begin(order(), policy));
        engine.abort(proceed, new IllegalStateException("boom"));

        assertThat(memory.find(proceed.key(), clock.instant())).isEmpty();
        assertThat(observer.outcomes).containsExactly(Outcome.RELEASED);
    }

    @Test
    void storesClientErrorsAndReleasesConfiguredOnes() {
        Decision.Proceed rejected = proceed(engine.begin(order(), policy));
        engine.finish(rejected, response(400, "{\"error\":\"invalid\"}"));
        assertThat(engine.begin(order(), policy)).isInstanceOf(Decision.Replay.class);

        Decision.Proceed limited =
                proceed(engine.begin(TestRequest.post("/orders").key("other").json(ORDER), policy));
        engine.finish(limited, response(429, "{}"));
        assertThat(memory.find(limited.key(), clock.instant())).isEmpty();
    }

    @Test
    @DisplayName("FR-09: after the lease, a retry with any request takes over and the old owner cannot complete")
    void takesOverAfterTheLease() {
        Decision.Proceed first = proceed(engine.begin(order(), policy));
        clock.advance(Duration.ofSeconds(31));

        Decision.Proceed second =
                proceed(engine.begin(TestRequest.post("/orders").key(KEY).json("{\"item\":\"pen\"}"), policy));
        engine.finish(first, response(201, ORDER));
        engine.finish(second, response(201, "{\"item\":\"pen\"}"));

        assertThat(second.token()).isNotEqualTo(first.token());
        assertThat(observer.leasesLost).containsExactly(new RouteRef("POST", "/orders"));
        StoredResponse stored = Objects.requireNonNull(
                memory.find(second.key(), clock.instant()).orElseThrow().response());
        assertThat(stored.body()).isEqualTo("{\"item\":\"pen\"}".getBytes(UTF_8));
    }

    @Test
    @DisplayName("FR-10: after the TTL, the same key runs as new with any request")
    void runsAgainAfterTheTtl() {
        engine.finish(proceed(engine.begin(order(), policy)), response(201, ORDER));
        clock.advance(Duration.ofHours(24).plusSeconds(1));

        assertThat(engine.begin(TestRequest.post("/orders").key(KEY).json("{\"item\":\"pen\"}"), policy))
                .isInstanceOf(Decision.Proceed.class);
    }

    @Test
    @DisplayName("FR-12: the same key from two client identities runs twice")
    void scopesKeysByClient() {
        Decision alice = engine.begin(order().client("alice"), policy);
        Decision bob = engine.begin(order().client("bob"), policy);

        assertThat(alice).isInstanceOf(Decision.Proceed.class);
        assertThat(bob).isInstanceOf(Decision.Proceed.class);
        assertThat(engine.begin(
                        order().client("bob"),
                        policy.toBuilder().scope(Scope.GLOBAL).build()))
                .isInstanceOf(Decision.Proceed.class);
    }

    @Test
    @DisplayName("FR-13: the same key on two routes runs twice")
    void scopesKeysByRoute() {
        assertThat(engine.begin(order(), policy)).isInstanceOf(Decision.Proceed.class);
        assertThat(engine.begin(TestRequest.post("/payments").key(KEY).json(ORDER), policy))
                .isInstanceOf(Decision.Proceed.class);
    }

    @Test
    @DisplayName("FR-18: a body over the limit answers 413 before the store is called")
    void rejectsLargeBodies() {
        IdempotencyPolicy small = policy.toBuilder().maxRequestBytes(10).build();

        Decision decision = engine.begin(order(), small);

        assertProblem(decision, ProblemType.REQUEST_TOO_LARGE);
        assertThat(observer.storeCalls).isEmpty();
    }

    @Test
    @DisplayName("FR-19: an oversized response is stored without its body and replayed with an empty body")
    void storesOversizedResponsesWithoutBody() {
        IdempotencyPolicy small = policy.toBuilder().maxResponseBytes(4).build();
        engine.finish(proceed(engine.begin(order(), small)), response(201, ORDER));
        engine.finish(
                proceed(engine.begin(TestRequest.post("/orders").key("k2").json(ORDER), small)), response(201, ORDER));

        Decision decision = engine.begin(order(), small);

        assertThat(decision).isInstanceOfSatisfying(Decision.Replay.class, replay -> {
            assertThat(replay.response().bodyOmitted()).isTrue();
            assertThat(replay.response().bodyLength()).isZero();
            assertThat(replay.response().headers()).isNotEmpty();
        });
        assertThat(observer.oversized).hasSize(2);
    }

    @Test
    @DisplayName("FR-19: with oversized=release, an oversized or truncated response releases the record")
    void releasesOversizedResponses() {
        IdempotencyPolicy release =
                policy.toBuilder().oversized(OversizedPolicy.RELEASE).build();
        Decision.Proceed proceed = proceed(engine.begin(order(), release));

        engine.finish(proceed, new CapturedResponse(201, Map.of(), new byte[0], true));

        assertThat(memory.find(proceed.key(), clock.instant())).isEmpty();
        assertThat(observer.outcomes).containsExactly(Outcome.RELEASED);
    }

    @Test
    @DisplayName("FR-21: with fail-closed, a store failure answers 503 with Retry-After: 1")
    void failsClosed() {
        store.failing = true;

        Decision decision = engine.begin(order(), policy);

        assertProblem(decision, ProblemType.STORE_UNAVAILABLE);
        assertThat(((Decision.Reject) decision).headers()).containsEntry("Retry-After", "1");
        assertThat(observer.outcomes).containsExactly(Outcome.STORE_UNAVAILABLE);
        assertThat(observer.storeCalls).containsExactly("CLAIM failed");
    }

    @Test
    @DisplayName("FR-21: with fail-open, a store failure runs the handler as a bypass")
    void failsOpen() {
        store.failing = true;

        Decision decision = engine(StoreFailurePolicy.FAIL_OPEN).begin(order(), policy);

        assertThat(decision).isEqualTo(new Decision.Bypass(BypassReason.FAIL_OPEN));
        assertThat(observer.outcomes).containsExactly(Outcome.FAIL_OPEN);
    }

    @Test
    @DisplayName("FR-40: when complete fails, finish does not throw and a retry within the lease gets 409")
    void survivesFailedCompletion() {
        Decision.Proceed proceed = proceed(engine.begin(order(), policy));
        store.failing = true;

        engine.finish(proceed, response(201, ORDER));

        store.failing = false;
        assertThat(observer.storeCalls).containsExactly("CLAIM", "COMPLETE failed");
        assertThat(observer.outcomes).containsExactly(Outcome.EXECUTED);
        assertProblem(engine.begin(order(), policy), ProblemType.REQUEST_IN_PROGRESS);
    }

    @Test
    @DisplayName("FR-40: a failed release is handled the same way")
    void survivesFailedRelease() {
        Decision.Proceed proceed = proceed(engine.begin(order(), policy));
        store.failing = true;

        engine.finish(proceed, response(500, "{}"));
        engine.abort(proceed, new IllegalStateException("boom"));

        store.failing = false;
        assertThat(observer.storeCalls).containsExactly("CLAIM", "RELEASE failed", "RELEASE failed");
        assertProblem(engine.begin(order(), policy), ProblemType.REQUEST_IN_PROGRESS);
    }

    @Test
    void encodesStoredBodiesAndDecodesReplays() {
        BodyCodec reversing = new BodyCodec() {
            @Override
            public byte[] encode(byte[] body) {
                return new StringBuilder(new String(body, UTF_8))
                        .reverse()
                        .toString()
                        .getBytes(UTF_8);
            }

            @Override
            public byte[] decode(byte[] stored) {
                return encode(stored);
            }
        };
        IdempotencyEngine encoding = IdempotencyEngine.builder()
                .store(memory)
                .clock(clock)
                .bodyCodec(reversing)
                .build();
        Decision.Proceed proceed = proceed(encoding.begin(order(), policy));
        encoding.finish(proceed, response(201, "abc"));

        StoredResponse stored = Objects.requireNonNull(
                memory.find(proceed.key(), clock.instant()).orElseThrow().response());
        assertThat(stored.body()).isEqualTo("cba".getBytes(UTF_8));
        assertThat(((Decision.Replay) encoding.begin(order(), policy))
                        .response()
                        .body())
                .isEqualTo("abc".getBytes(UTF_8));
    }

    @Test
    void usesTheConfiguredHeaderName() {
        IdempotencyEngine custom =
                IdempotencyEngine.builder().store(memory).headerName("X-Key").build();

        assertThat(custom.headerName()).isEqualTo("X-Key");
        assertThat(custom.begin(TestRequest.post("/a").header("X-Key", "\"k\""), policy))
                .isInstanceOf(Decision.Proceed.class);
        Decision missing = custom.begin(TestRequest.post("/a").key("k"), policy);
        assertProblem(missing, ProblemType.MISSING_KEY);
        assertThat(((Decision.Reject) missing).problem().detail()).contains("X-Key");
    }

    private IdempotencyEngine engine(StoreFailurePolicy onStoreFailure) {
        return IdempotencyEngine.builder()
                .store(store)
                .clock(clock)
                .observer(observer)
                .onStoreFailure(onStoreFailure)
                .build();
    }

    private static TestRequest order() {
        return TestRequest.post("/orders").key(KEY).json(ORDER);
    }

    private static CapturedResponse response(int status, String body) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of("application/json"));
        headers.put("Location", List.of("/orders/1"));
        headers.put("Set-Cookie", List.of("session=secret"));
        headers.put("X-Trace", List.of("abc"));
        return new CapturedResponse(status, headers, body.getBytes(UTF_8), false);
    }

    private static Decision.Proceed proceed(Decision decision) {
        assertThat(decision).isInstanceOf(Decision.Proceed.class);
        return (Decision.Proceed) decision;
    }

    private static void assertProblem(Decision decision, ProblemType type) {
        assertThat(decision).isInstanceOfSatisfying(Decision.Reject.class, reject -> {
            assertThat(reject.problem().status()).isEqualTo(type.status());
            assertThat(reject.problem().type().toString()).endsWith("/" + type.slug());
        });
    }

    /** Delegates to a store, or throws while {@code failing} is set. */
    private static final class FlakyStore implements IdempotencyStore {

        private final IdempotencyStore delegate;
        volatile boolean failing;

        FlakyStore(IdempotencyStore delegate) {
            this.delegate = delegate;
        }

        private void check() {
            if (failing) {
                throw new IdempotencyStoreException("store down");
            }
        }

        @Override
        public ClaimResult claim(ClaimRequest request) {
            check();
            return delegate.claim(request);
        }

        @Override
        public boolean complete(
                ScopeKey key, OwnerToken token, StoredResponse response, Instant completedAt, Instant expiresAt) {
            check();
            return delegate.complete(key, token, response, completedAt, expiresAt);
        }

        @Override
        public void release(ScopeKey key, OwnerToken token) {
            check();
            delegate.release(key, token);
        }

        @Override
        public Optional<IdempotencyRecord> find(ScopeKey key, Instant now) {
            check();
            return delegate.find(key, now);
        }

        @Override
        public int purgeExpired(Instant now, int batchSize) {
            check();
            return delegate.purgeExpired(now, batchSize);
        }

        @Override
        public StoreHealth health() {
            return delegate.health();
        }
    }
}
