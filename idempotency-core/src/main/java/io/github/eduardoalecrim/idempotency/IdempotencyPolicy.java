// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import io.github.eduardoalecrim.idempotency.Idempotent.Scope;
import io.github.eduardoalecrim.idempotency.fingerprint.FingerprintStrategy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The policy of one idempotent operation.
 *
 * @param required whether a request without a key is rejected
 * @param ttl how long a completed record is replayed
 * @param lease how long an in-progress record protects a running request; shorter than {@code ttl}
 * @param scope whether keys are separate per client identity
 * @param fingerprint the strategy that fingerprints requests
 * @param fingerprintHeaders request headers added to the fingerprint, in lowercase
 * @param statusPolicy which statuses are stored
 * @param replayHeaders response headers stored and restored on replay, in lowercase
 * @param maxRequestBytes the largest request body accepted
 * @param maxResponseBytes the largest response body stored
 * @param oversized what happens to a larger response
 */
public record IdempotencyPolicy(
        boolean required,
        Duration ttl,
        Duration lease,
        Scope scope,
        FingerprintStrategy fingerprint,
        List<String> fingerprintHeaders,
        StatusPolicy statusPolicy,
        Set<String> replayHeaders,
        long maxRequestBytes,
        long maxResponseBytes,
        OversizedPolicy oversized) {

    /** Headers restored on replay by default. */
    public static final Set<String> DEFAULT_REPLAY_HEADERS =
            Set.of("content-type", "content-language", "location", "etag", "last-modified", "cache-control");

    /**
     * Headers that are never stored or replayed: credentials, hop-by-hop headers, and {@code content-length}, which
     * the adapter writes for the replayed body.
     */
    public static final Set<String> DENIED_REPLAY_HEADERS = Set.of(
            "set-cookie",
            "authorization",
            "proxy-authenticate",
            "www-authenticate",
            "connection",
            "keep-alive",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length");

    /** Largest body the engine buffers, the practical limit of a Java array. */
    public static final long MAX_BODY_BYTES = Integer.MAX_VALUE - 8;

    /**
     * Lowercases and copies the header lists.
     *
     * @throws IllegalArgumentException if a duration is not positive, the lease is not shorter than the TTL, a limit is
     *     out of range, or a replay header is denied
     */
    public IdempotencyPolicy {
        if (ttl.isZero() || ttl.isNegative() || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("ttl and lease must be positive");
        }
        if (lease.compareTo(ttl) >= 0) {
            throw new IllegalArgumentException("lease (" + lease + ") must be shorter than ttl (" + ttl + ")");
        }
        checkLimit("maxRequestBytes", maxRequestBytes);
        checkLimit("maxResponseBytes", maxResponseBytes);
        fingerprintHeaders = List.copyOf(lowercase(fingerprintHeaders));
        replayHeaders = Set.copyOf(lowercase(replayHeaders));
        for (String header : replayHeaders) {
            if (DENIED_REPLAY_HEADERS.contains(header)) {
                throw new IllegalArgumentException("header " + header + " must never be replayed");
            }
        }
    }

    /** Returns a builder with the library defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder that starts from this policy. */
    public Builder toBuilder() {
        return new Builder()
                .required(required)
                .ttl(ttl)
                .lease(lease)
                .scope(scope)
                .fingerprint(fingerprint)
                .fingerprintHeaders(fingerprintHeaders)
                .statusPolicy(statusPolicy)
                .replayHeaders(replayHeaders)
                .maxRequestBytes(maxRequestBytes)
                .maxResponseBytes(maxResponseBytes)
                .oversized(oversized);
    }

    private static void checkLimit(String name, long value) {
        if (value < 0 || value > MAX_BODY_BYTES) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_BODY_BYTES + ": " + value);
        }
    }

    private static List<String> lowercase(Iterable<String> headers) {
        List<String> out = new ArrayList<>();
        for (String header : headers) {
            String name = header.trim().toLowerCase(Locale.ROOT);
            if (!out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** Builds an {@link IdempotencyPolicy}. */
    public static final class Builder {

        private boolean required = true;
        private Duration ttl = Duration.ofHours(24);
        private Duration lease = Duration.ofSeconds(30);
        private Scope scope = Scope.CLIENT;
        private FingerprintStrategy fingerprint = new FingerprintStrategy.Default();
        private List<String> fingerprintHeaders = List.of();
        private StatusPolicy statusPolicy = StatusPolicy.DEFAULT;
        private Set<String> replayHeaders = DEFAULT_REPLAY_HEADERS;
        private long maxRequestBytes = 1024 * 1024;
        private long maxResponseBytes = 1024 * 1024;
        private OversizedPolicy oversized = OversizedPolicy.STORE_WITHOUT_BODY;

        private Builder() {}

        /** Sets whether a request without a key is rejected; default {@code true}. */
        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        /** Sets the TTL; default 24 hours. */
        public Builder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        /** Sets the lease; default 30 seconds. */
        public Builder lease(Duration lease) {
            this.lease = lease;
            return this;
        }

        /** Sets the scope; default {@link Scope#CLIENT}. */
        public Builder scope(Scope scope) {
            this.scope = scope;
            return this;
        }

        /** Sets the fingerprint strategy; default {@link FingerprintStrategy.Default}. */
        public Builder fingerprint(FingerprintStrategy fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        /** Sets the request headers added to the fingerprint; default none. */
        public Builder fingerprintHeaders(List<String> fingerprintHeaders) {
            this.fingerprintHeaders = fingerprintHeaders;
            return this;
        }

        /** Sets the status policy; default {@link StatusPolicy#DEFAULT}. */
        public Builder statusPolicy(StatusPolicy statusPolicy) {
            this.statusPolicy = statusPolicy;
            return this;
        }

        /** Sets the replayed response headers; default {@link #DEFAULT_REPLAY_HEADERS}. */
        public Builder replayHeaders(Set<String> replayHeaders) {
            this.replayHeaders = replayHeaders;
            return this;
        }

        /** Sets the request body limit; default 1 MiB. */
        public Builder maxRequestBytes(long maxRequestBytes) {
            this.maxRequestBytes = maxRequestBytes;
            return this;
        }

        /** Sets the stored response body limit; default 1 MiB. */
        public Builder maxResponseBytes(long maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /** Sets what happens to larger responses; default {@link OversizedPolicy#STORE_WITHOUT_BODY}. */
        public Builder oversized(OversizedPolicy oversized) {
            this.oversized = oversized;
            return this;
        }

        /** Builds the policy, validated as described in {@link IdempotencyPolicy}. */
        public IdempotencyPolicy build() {
            return new IdempotencyPolicy(
                    required,
                    ttl,
                    lease,
                    scope,
                    fingerprint,
                    fingerprintHeaders,
                    statusPolicy,
                    replayHeaders,
                    maxRequestBytes,
                    maxResponseBytes,
                    oversized);
        }
    }
}
