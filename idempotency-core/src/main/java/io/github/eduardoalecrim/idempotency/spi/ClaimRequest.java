// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.time.Instant;

/**
 * Arguments of {@link IdempotencyStore#claim(ClaimRequest)}.
 *
 * @param key the scope key to claim
 * @param parts the values the scope key was computed from
 * @param fingerprint the fingerprint of the claiming request
 * @param now the current time, from the application clock
 * @param leaseUntil the end of the lease of the new record
 * @param expiresAt the expiry of the new record
 */
public record ClaimRequest(
        ScopeKey key, ScopeParts parts, Fingerprint fingerprint, Instant now, Instant leaseUntil, Instant expiresAt) {

    /** @throws IllegalArgumentException if the lease does not end after {@code now} or the record expires before it */
    public ClaimRequest {
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("lease must end after now");
        }
        if (expiresAt.isBefore(leaseUntil)) {
            throw new IllegalArgumentException("record must not expire before its lease ends");
        }
    }

    /** Returns the in-progress record this claim creates when it acquires the key. */
    public IdempotencyRecord toRecord(OwnerToken ownerToken) {
        return IdempotencyRecord.inProgress(key, parts, fingerprint, ownerToken, leaseUntil, now, expiresAt);
    }
}
