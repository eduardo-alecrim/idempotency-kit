// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What a store keeps for one scope key.
 *
 * <p>An {@link RecordState#IN_PROGRESS} record has an owner token and a lease end and no response. A
 * {@link RecordState#COMPLETED} record has a response and a completion time and no owner token or lease end.
 *
 * @param key the scope key
 * @param parts the values the scope key was computed from
 * @param fingerprint the fingerprint of the request that claimed the record
 * @param state the state
 * @param ownerToken the token of the request holding the record, while in progress
 * @param leaseUntil the end of the lease, while in progress
 * @param response the stored response, once completed
 * @param createdAt when the record was claimed
 * @param completedAt when the record was completed
 * @param expiresAt when the record expires
 */
public record IdempotencyRecord(
        ScopeKey key,
        ScopeParts parts,
        Fingerprint fingerprint,
        RecordState state,
        @Nullable OwnerToken ownerToken,
        @Nullable Instant leaseUntil,
        @Nullable StoredResponse response,
        Instant createdAt,
        @Nullable Instant completedAt,
        Instant expiresAt) {

    /** @throws IllegalArgumentException if the fields do not match the state */
    public IdempotencyRecord {
        if (state == RecordState.IN_PROGRESS) {
            if (ownerToken == null || leaseUntil == null) {
                throw new IllegalArgumentException("an in-progress record needs an owner token and a lease end");
            }
            if (response != null || completedAt != null) {
                throw new IllegalArgumentException("an in-progress record has no response");
            }
        } else {
            if (response == null || completedAt == null) {
                throw new IllegalArgumentException("a completed record needs a response and a completion time");
            }
            if (ownerToken != null || leaseUntil != null) {
                throw new IllegalArgumentException("a completed record has no owner token or lease end");
            }
        }
    }

    /** Returns a new in-progress record. */
    public static IdempotencyRecord inProgress(
            ScopeKey key,
            ScopeParts parts,
            Fingerprint fingerprint,
            OwnerToken ownerToken,
            Instant leaseUntil,
            Instant createdAt,
            Instant expiresAt) {
        return new IdempotencyRecord(
                key,
                parts,
                fingerprint,
                RecordState.IN_PROGRESS,
                ownerToken,
                leaseUntil,
                null,
                createdAt,
                null,
                expiresAt);
    }

    /** Returns this record completed with {@code response}. */
    public IdempotencyRecord complete(StoredResponse response, Instant completedAt, Instant expiresAt) {
        return new IdempotencyRecord(
                key,
                parts,
                fingerprint,
                RecordState.COMPLETED,
                null,
                null,
                response,
                createdAt,
                completedAt,
                expiresAt);
    }

    /**
     * Whether the record counts as absent at {@code now}: its lease ended while in progress, or its TTL elapsed. An
     * expired record behaves as if it did not exist, in every store.
     */
    public boolean isExpired(Instant now) {
        if (expiresAt.isBefore(now)) {
            return true;
        }
        return state == RecordState.IN_PROGRESS && leaseUntil != null && leaseUntil.isBefore(now);
    }

    /** Whether {@code token} holds this record. */
    public boolean isOwnedBy(OwnerToken token) {
        return state == RecordState.IN_PROGRESS && token.equals(ownerToken);
    }
}
