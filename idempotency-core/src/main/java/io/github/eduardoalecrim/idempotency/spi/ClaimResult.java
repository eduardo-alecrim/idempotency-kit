// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

/** Result of {@link IdempotencyStore#claim(ClaimRequest)}. */
public sealed interface ClaimResult {

    /**
     * The claim created a new in-progress record, or took over an expired one.
     *
     * @param token the owner token of the new record
     */
    record Acquired(OwnerToken token) implements ClaimResult {}

    /**
     * A live record already holds the scope key.
     *
     * @param record the record, which is not expired at the claim's {@code now}
     */
    record Existing(IdempotencyRecord record) implements ClaimResult {}
}
