// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import io.github.eduardoalecrim.idempotency.internal.Hex;

/**
 * Identity of a record: the SHA-256 of its scope parts, as 64 lowercase hex characters.
 *
 * @param value the hex digest
 */
public record ScopeKey(String value) {

    /** Number of hex characters in a scope key. */
    public static final int LENGTH = 64;

    /** @throws IllegalArgumentException if {@code value} is not 64 lowercase hex characters */
    public ScopeKey {
        if (!Hex.isLowerHex(value, LENGTH)) {
            throw new IllegalArgumentException("scope key must be 64 lowercase hex characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
