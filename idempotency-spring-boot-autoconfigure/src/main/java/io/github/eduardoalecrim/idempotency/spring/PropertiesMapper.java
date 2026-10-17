// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring;

import io.github.eduardoalecrim.idempotency.IdempotencyPolicy;
import io.github.eduardoalecrim.idempotency.StatusPolicy;
import io.github.eduardoalecrim.idempotency.fingerprint.FingerprintStrategy;
import io.github.eduardoalecrim.idempotency.internal.Durations;
import io.github.eduardoalecrim.idempotency.key.IdempotencyKeyParser;
import io.github.eduardoalecrim.idempotency.problem.ProblemFactory;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.unit.DataSize;

/** Builds core objects from {@link IdempotencyProperties}, naming the property behind every invalid value. */
final class PropertiesMapper {

    private static final Set<String> ALLOWED_METHODS = Set.of("POST", "PATCH", "PUT", "DELETE");

    private PropertiesMapper() {}

    static IdempotencyPolicy defaultPolicy(IdempotencyProperties properties) {
        IdempotencyProperties.Response response = properties.getResponse();
        if (properties.getLease().compareTo(properties.getDefaultTtl()) >= 0) {
            throw IdempotencyConfigurationException.property(
                    "idempotency.lease",
                    "the lease (" + Durations.format(properties.getLease())
                            + ") must be shorter than idempotency.default-ttl ("
                            + Durations.format(properties.getDefaultTtl()) + ")");
        }
        for (String header : response.getReplayHeaders()) {
            if (IdempotencyPolicy.DENIED_REPLAY_HEADERS.contains(header.trim().toLowerCase(Locale.ROOT))) {
                throw IdempotencyConfigurationException.property(
                        "idempotency.response.replay-headers", header + " must never be stored or replayed");
            }
        }
        StatusPolicy statusPolicy;
        try {
            statusPolicy = StatusPolicy.of(response.getStoreStatuses(), response.getReleaseStatuses());
        } catch (IllegalArgumentException e) {
            throw IdempotencyConfigurationException.property(
                    "idempotency.response.store-statuses", String.valueOf(e.getMessage()));
        }
        try {
            return IdempotencyPolicy.builder()
                    .ttl(properties.getDefaultTtl())
                    .lease(properties.getLease())
                    .fingerprint(new FingerprintStrategy.Default(
                            properties.getFingerprint().isCanonicalizeJson()))
                    .fingerprintHeaders(properties.getFingerprint().getHeaders())
                    .statusPolicy(statusPolicy)
                    .replayHeaders(new LinkedHashSet<>(response.getReplayHeaders()))
                    .maxRequestBytes(bytes(
                            "idempotency.request.max-body-size",
                            properties.getRequest().getMaxBodySize()))
                    .maxResponseBytes(bytes("idempotency.response.max-body-size", response.getMaxBodySize()))
                    .oversized(response.getOversized())
                    .build();
        } catch (IllegalArgumentException e) {
            throw IdempotencyConfigurationException.property("idempotency.default-ttl", String.valueOf(e.getMessage()));
        }
    }

    static IdempotencyKeyParser keyParser(IdempotencyProperties properties) {
        IdempotencyProperties.Key key = properties.getKey();
        if (key.getMaxLength() < 1) {
            throw IdempotencyConfigurationException.property("idempotency.key.max-length", "it must be positive");
        }
        return new IdempotencyKeyParser(key.getParsing(), key.getMaxLength(), key.getFormat());
    }

    static ProblemFactory problemFactory(IdempotencyProperties properties) {
        try {
            return new ProblemFactory(properties.getProblems().getBaseUri(), properties.getHeaderName());
        } catch (IllegalArgumentException e) {
            throw IdempotencyConfigurationException.property(
                    "idempotency.problems.base-uri", String.valueOf(e.getMessage()));
        }
    }

    static Set<String> methods(IdempotencyProperties properties) {
        Set<String> methods = new LinkedHashSet<>();
        for (String method : properties.getMethods()) {
            String name = method.trim().toUpperCase(Locale.ROOT);
            if (!ALLOWED_METHODS.contains(name)) {
                throw IdempotencyConfigurationException.property(
                        "idempotency.methods", name + " is not allowed; use POST, PATCH, PUT or DELETE");
            }
            methods.add(name);
        }
        return Set.copyOf(methods);
    }

    static void checkClientIdentity(IdempotencyProperties properties) {
        IdempotencyProperties.ClientIdentity identity = properties.getClientIdentity();
        String header = identity.getHeader();
        if (identity.getSource() == IdempotencyProperties.ClientIdentity.Source.HEADER
                && (header == null || header.isBlank())) {
            throw IdempotencyConfigurationException.property(
                    "idempotency.client-identity.header", "it is required when the source is header");
        }
    }

    private static long bytes(String property, DataSize size) {
        long bytes = size.toBytes();
        if (bytes < 0 || bytes > IdempotencyPolicy.MAX_BODY_BYTES) {
            throw IdempotencyConfigurationException.property(
                    property, "it must be between 0 and " + IdempotencyPolicy.MAX_BODY_BYTES + " bytes");
        }
        return bytes;
    }
}
