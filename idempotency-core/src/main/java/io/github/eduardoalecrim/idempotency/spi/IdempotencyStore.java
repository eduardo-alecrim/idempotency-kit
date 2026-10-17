// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.time.Instant;
import java.util.Optional;

/**
 * Where records live.
 *
 * <p>Implementations must be thread-safe and make {@link #claim}, {@link #complete} and {@link #release} atomic per
 * scope key, across every instance that shares the store. Every time comparison uses the instants passed in, never the
 * store's own clock, and an expired record behaves as if it did not exist (see
 * {@link IdempotencyRecord#isExpired(Instant)}). Failures are thrown as {@link IdempotencyStoreException}.
 */
public interface IdempotencyStore {

    /**
     * Creates an in-progress record for the scope key, or reports the live record that holds it.
     *
     * <p>When no record exists, or the existing one is expired at {@code request.now()}, the store writes
     * {@code request.toRecord(token)} with a new random token and returns {@link ClaimResult.Acquired}. Otherwise it
     * returns {@link ClaimResult.Existing} and changes nothing.
     */
    ClaimResult claim(ClaimRequest request);

    /**
     * Stores the response of an in-progress record held by {@code token}.
     *
     * @return {@code false}, changing nothing, when the record is missing, completed or held by another token
     */
    boolean complete(ScopeKey key, OwnerToken token, StoredResponse response, Instant completedAt, Instant expiresAt);

    /** Deletes the in-progress record held by {@code token}; does nothing when another token holds it. */
    void release(ScopeKey key, OwnerToken token);

    /** Returns the record for the scope key unless it is missing or expired at {@code now}. */
    Optional<IdempotencyRecord> find(ScopeKey key, Instant now);

    /**
     * Deletes up to {@code batchSize} records whose expiry is before {@code now}. Stores whose records expire on their
     * own return 0.
     *
     * @return the number of deleted records
     */
    int purgeExpired(Instant now, int batchSize);

    /** Checks that the store answers, cheaply. */
    StoreHealth health();
}
