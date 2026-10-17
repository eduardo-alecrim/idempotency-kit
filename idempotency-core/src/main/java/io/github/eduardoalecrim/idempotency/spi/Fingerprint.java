// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import io.github.eduardoalecrim.idempotency.internal.Hex;

/**
 * Digest of the parts of a request that decide whether two requests are the same, as 64 lowercase hex characters.
 *
 * @param value the hex digest
 */
public record Fingerprint(String value) {

    /** Number of hex characters in a fingerprint. */
    public static final int LENGTH = 64;

    /** @throws IllegalArgumentException if {@code value} is not 64 lowercase hex characters */
    public Fingerprint {
        if (!Hex.isLowerHex(value, LENGTH)) {
            throw new IllegalArgumentException("fingerprint must be 64 lowercase hex characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
