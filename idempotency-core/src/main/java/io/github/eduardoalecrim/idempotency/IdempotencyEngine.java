// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import io.github.eduardoalecrim.idempotency.Idempotent.Scope;
import io.github.eduardoalecrim.idempotency.fingerprint.FingerprintInput;
import io.github.eduardoalecrim.idempotency.fingerprint.ScopeKeys;
import io.github.eduardoalecrim.idempotency.key.IdempotencyKeyParser;
import io.github.eduardoalecrim.idempotency.key.KeyParseResult;
import io.github.eduardoalecrim.idempotency.problem.ProblemFactory;
import io.github.eduardoalecrim.idempotency.spi.ClaimRequest;
import io.github.eduardoalecrim.idempotency.spi.ClaimResult;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyRecord;
import io.github.eduardoalecrim.idempotency.spi.IdempotencyStore;
import io.github.eduardoalecrim.idempotency.spi.RecordState;
import io.github.eduardoalecrim.idempotency.spi.ScopeKey;
import io.github.eduardoalecrim.idempotency.spi.ScopeParts;
import io.github.eduardoalecrim.idempotency.spi.StoredResponse;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The idempotency protocol. Adapters translate their requests into {@link IncomingRequest}, act on the returned
 * {@link Decision}, and report the handler's response with {@link #finish} or its failure with {@link #abort}.
 *
 * <p>Thread-safe. Store calls are blocking (ADR 0012); store timeouts are configured on the store itself.
 */
public final class IdempotencyEngine {

    /** Response header that marks a replayed response. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private static final System.Logger LOG = System.getLogger(IdempotencyEngine.class.getName());

    private final IdempotencyStore store;
    private final Clock clock;
    private final IdempotencyObserver observer;
    private final ProblemFactory problems;
    private final BodyCodec bodyCodec;
    private final IdempotencyKeyParser keyParser;
    private final String headerName;
    private final StoreFailurePolicy onStoreFailure;
    // Routes are fixed at startup, so this set stays bounded.
    private final Set<RouteRef> oversizedWarned = ConcurrentHashMap.newKeySet();

    private IdempotencyEngine(Builder builder) {
        this.store = Objects.requireNonNull(builder.store, "store");
        this.clock = builder.clock;
        this.observer = builder.observer;
        this.headerName = builder.headerName;
        this.problems = builder.problems != null
                ? builder.problems
                : new ProblemFactory(ProblemFactory.DEFAULT_BASE_URI, headerName);
        this.bodyCodec = builder.bodyCodec;
        this.keyParser = builder.keyParser;
        this.onStoreFailure = builder.onStoreFailure;
    }

    /** Returns a builder; only the store is required. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the name of the request header carrying the key. */
    public String headerName() {
        return headerName;
    }

    /**
     * Parses the key, checks the body limit, computes the fingerprint and scope key, and claims the scope key.
     *
     * @throws java.io.UncheckedIOException if reading the body fails
     */
    public Decision begin(IncomingRequest request, IdempotencyPolicy policy) {
        Instant startedAt = clock.instant();
        RouteRef route = new RouteRef(request.method(), request.routeTemplate());
        List<String> keyHeader = request.headerValues(headerName);
        if (keyHeader.isEmpty()) {
            if (policy.required()) {
                return reject(
                        Outcome.MISSING_KEY,
                        route,
                        startedAt,
                        new Decision.Reject(problems.missingKey(request.path()), Map.of()));
            }
            report(Outcome.NO_KEY, route, startedAt);
            return new Decision.Bypass(BypassReason.NO_KEY);
        }
        KeyParseResult parsed = keyParser.parse(String.join(", ", keyHeader));
        if (parsed instanceof KeyParseResult.Invalid invalid) {
            return reject(
                    Outcome.INVALID_KEY,
                    route,
                    startedAt,
                    new Decision.Reject(problems.invalidKey(request.path(), invalid.reason()), Map.of()));
        }
        String key = ((KeyParseResult.Valid) parsed).key();

        byte[] body = request.body((int) policy.maxRequestBytes());
        if (body.length > policy.maxRequestBytes()) {
            return reject(
                    Outcome.REQUEST_TOO_LARGE,
                    route,
                    startedAt,
                    new Decision.Reject(
                            problems.requestTooLarge(request.path(), policy.maxRequestBytes(), key), Map.of()));
        }
        Fingerprint fingerprint = policy.fingerprint().fingerprint(fingerprintInput(request, policy, body));
        String clientId =
                policy.scope() == Scope.CLIENT ? request.clientIdentity().orElse("") : "";
        ScopeParts parts = new ScopeParts(key, clientId, request.method(), request.routeTemplate());
        ScopeKey scopeKey = ScopeKeys.compute(policy.scope(), parts);

        Instant now = now();
        ClaimRequest claim =
                new ClaimRequest(scopeKey, parts, fingerprint, now, now.plus(policy.lease()), now.plus(policy.ttl()));
        ClaimResult result;
        try {
            result = call(StoreOperation.CLAIM, () -> store.claim(claim));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Idempotency store failed to claim a key for " + route, e);
            if (onStoreFailure == StoreFailurePolicy.FAIL_OPEN) {
                report(Outcome.FAIL_OPEN, route, startedAt);
                return new Decision.Bypass(BypassReason.FAIL_OPEN);
            }
            return reject(
                    Outcome.STORE_UNAVAILABLE,
                    route,
                    startedAt,
                    new Decision.Reject(problems.storeUnavailable(request.path(), key), Map.of("Retry-After", "1")));
        }

        if (result instanceof ClaimResult.Acquired acquired) {
            LOG.log(Level.DEBUG, () -> "Claimed " + scopeKey + " for " + route);
            return new Decision.Proceed(scopeKey, acquired.token(), policy, route, startedAt);
        }
        IdempotencyRecord record = ((ClaimResult.Existing) result).record();
        if (!record.fingerprint().equals(fingerprint)) {
            return reject(
                    Outcome.KEY_REUSE,
                    route,
                    startedAt,
                    new Decision.Reject(problems.keyReuse(request.path(), key), Map.of()));
        }
        if (record.state() == RecordState.IN_PROGRESS) {
            Instant leaseUntil = Objects.requireNonNull(record.leaseUntil());
            return reject(
                    Outcome.IN_PROGRESS,
                    route,
                    startedAt,
                    new Decision.Reject(
                            problems.requestInProgress(request.path(), key),
                            Map.of("Retry-After", Long.toString(retryAfterSeconds(now, leaseUntil)))));
        }
        StoredResponse stored = Objects.requireNonNull(record.response());
        report(Outcome.REPLAYED, route, startedAt);
        return new Decision.Replay(new StoredResponse(
                stored.status(),
                stored.headers(),
                stored.bodyOmitted() ? stored.body() : bodyCodec.decode(stored.body()),
                stored.bodyOmitted()));
    }

    /**
     * Applies the status policy to the handler's response and completes or releases the record. A store failure is
     * logged and reported, never thrown: the handler's effects already happened, so its response must still be sent.
     */
    public void finish(Decision.Proceed proceed, CapturedResponse response) {
        IdempotencyPolicy policy = proceed.policy();
        RouteRef route = proceed.route();
        if (!policy.statusPolicy().isStorable(response.status())) {
            release(proceed);
            report(Outcome.RELEASED, route, proceed.startedAt());
            return;
        }
        List<StoredResponse.Header> headers = replayHeaders(response, policy);
        StoredResponse stored;
        if (response.truncated() || response.bodyLength() > policy.maxResponseBytes()) {
            observer.onResponseOversized(route);
            if (oversizedWarned.add(route)) {
                LOG.log(
                        Level.WARNING,
                        "A response of " + route + " is larger than the response limit of " + policy.maxResponseBytes()
                                + " bytes and is handled with " + policy.oversized());
            }
            if (policy.oversized() == OversizedPolicy.RELEASE) {
                release(proceed);
                report(Outcome.RELEASED, route, proceed.startedAt());
                return;
            }
            stored = new StoredResponse(response.status(), headers, new byte[0], true);
        } else {
            stored = new StoredResponse(response.status(), headers, bodyCodec.encode(response.body()), false);
        }

        Instant now = now();
        try {
            boolean completed = call(
                    StoreOperation.COMPLETE,
                    () -> store.complete(proceed.key(), proceed.token(), stored, now, now.plus(policy.ttl())));
            if (!completed) {
                LOG.log(
                        Level.WARNING,
                        "A request to " + route
                                + " outlived its lease and another request took over its record; its response was not stored");
                observer.onLeaseLost(route);
            }
        } catch (RuntimeException e) {
            LOG.log(
                    Level.WARNING,
                    "Idempotency store failed to complete a record for " + route
                            + "; the record stays in progress until its lease ends",
                    e);
        }
        report(Outcome.EXECUTED, route, proceed.startedAt());
    }

    /** Releases the record after the handler threw, so that a retry runs again. Never throws. */
    public void abort(Decision.Proceed proceed, Throwable cause) {
        LOG.log(
                Level.DEBUG,
                () -> "Releasing " + proceed.key() + " after "
                        + cause.getClass().getName());
        release(proceed);
        report(Outcome.RELEASED, proceed.route(), proceed.startedAt());
    }

    private void release(Decision.Proceed proceed) {
        try {
            run(StoreOperation.RELEASE, () -> store.release(proceed.key(), proceed.token()));
        } catch (RuntimeException e) {
            LOG.log(
                    Level.WARNING,
                    "Idempotency store failed to release a record for " + proceed.route()
                            + "; retries get 409 until its lease ends",
                    e);
        }
    }

    private <T> T call(StoreOperation operation, Supplier<T> call) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            T result = call.get();
            success = true;
            return result;
        } finally {
            observer.onStoreCall(operation, Duration.ofNanos(System.nanoTime() - start), success);
        }
    }

    private void run(StoreOperation operation, Runnable call) {
        long start = System.nanoTime();
        boolean success = false;
        try {
            call.run();
            success = true;
        } finally {
            observer.onStoreCall(operation, Duration.ofNanos(System.nanoTime() - start), success);
        }
    }

    private Decision.Reject reject(Outcome outcome, RouteRef route, Instant startedAt, Decision.Reject reject) {
        report(outcome, route, startedAt);
        return reject;
    }

    private void report(Outcome outcome, RouteRef route, Instant startedAt) {
        Duration elapsed = Duration.between(startedAt, clock.instant());
        observer.onOutcome(outcome, route, elapsed.isNegative() ? Duration.ZERO : elapsed);
    }

    // Millisecond precision is what every store keeps, so records read back equal to what was written.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static FingerprintInput fingerprintInput(IncomingRequest request, IdempotencyPolicy policy, byte[] body) {
        List<FingerprintInput.Header> headers = new ArrayList<>();
        for (String name : policy.fingerprintHeaders()) {
            headers.add(new FingerprintInput.Header(name, request.headerValues(name)));
        }
        return new FingerprintInput(request.method(), request.routeTemplate(), request.contentType(), headers, body);
    }

    private static List<StoredResponse.Header> replayHeaders(CapturedResponse response, IdempotencyPolicy policy) {
        List<StoredResponse.Header> headers = new ArrayList<>();
        response.headers().forEach((name, values) -> {
            if (policy.replayHeaders().contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    try {
                        headers.add(new StoredResponse.Header(name, value));
                    } catch (IllegalArgumentException e) {
                        LOG.log(Level.DEBUG, () -> "Skipping header " + name + ": " + e.getMessage());
                    }
                }
            }
        });
        return headers;
    }

    // Whole seconds, rounded up and at least 1, so that a retry never arrives before the lease ends.
    static long retryAfterSeconds(Instant now, Instant leaseUntil) {
        long millis = Duration.between(now, leaseUntil).toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }

    /** Builds an {@link IdempotencyEngine}. */
    public static final class Builder {

        private @Nullable IdempotencyStore store;
        private Clock clock = Clock.systemUTC();
        private IdempotencyObserver observer = IdempotencyObserver.NOOP;
        private @Nullable ProblemFactory problems;
        private BodyCodec bodyCodec = BodyCodec.IDENTITY;
        private IdempotencyKeyParser keyParser = IdempotencyKeyParser.defaults();
        private String headerName = "Idempotency-Key";
        private StoreFailurePolicy onStoreFailure = StoreFailurePolicy.FAIL_CLOSED;

        private Builder() {}

        /** Sets the store; required. */
        public Builder store(IdempotencyStore store) {
            this.store = store;
            return this;
        }

        /** Sets the clock every record time comes from (ADR 0006); default UTC system clock. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        /** Sets the observer; default none. */
        public Builder observer(IdempotencyObserver observer) {
            this.observer = observer;
            return this;
        }

        /** Sets the problem factory; default the documentation base URI and the configured header name. */
        public Builder problemFactory(ProblemFactory problems) {
            this.problems = problems;
            return this;
        }

        /** Sets the codec of stored bodies; default {@link BodyCodec#IDENTITY}. */
        public Builder bodyCodec(BodyCodec bodyCodec) {
            this.bodyCodec = bodyCodec;
            return this;
        }

        /** Sets the key parser; default {@link IdempotencyKeyParser#defaults()}. */
        public Builder keyParser(IdempotencyKeyParser keyParser) {
            this.keyParser = keyParser;
            return this;
        }

        /** Sets the request header carrying the key; default {@code Idempotency-Key}. */
        public Builder headerName(String headerName) {
            this.headerName = headerName;
            return this;
        }

        /** Sets what happens when the store fails before the handler; default {@link StoreFailurePolicy#FAIL_CLOSED}. */
        public Builder onStoreFailure(StoreFailurePolicy onStoreFailure) {
            this.onStoreFailure = onStoreFailure;
            return this;
        }

        /** Builds the engine; throws {@link NullPointerException} if no store was set. */
        public IdempotencyEngine build() {
            return new IdempotencyEngine(this);
        }
    }
}
