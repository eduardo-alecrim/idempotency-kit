// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Proof, issued by a successful claim, that a request holds an in-progress record.
 *
 * @param value a random UUID in canonical lowercase form
 */
public record OwnerToken(String value) {

    /** Number of characters in an owner token. */
    public static final int LENGTH = 36;

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** @throws IllegalArgumentException if {@code value} is not a canonical lowercase UUID */
    public OwnerToken {
        if (!CANONICAL_UUID.matcher(value).matches()) {
            throw new IllegalArgumentException("owner token must be a canonical lowercase UUID");
        }
    }

    /** Returns a new token from a random UUID. */
    public static OwnerToken random() {
        return new OwnerToken(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
