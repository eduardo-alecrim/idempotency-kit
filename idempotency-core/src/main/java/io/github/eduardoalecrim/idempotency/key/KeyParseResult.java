// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

/** Result of {@link IdempotencyKeyParser#parse(String)}. */
public sealed interface KeyParseResult {

    /**
     * The header holds a usable key.
     *
     * @param key the key after unescaping
     */
    record Valid(String key) implements KeyParseResult {}

    /**
     * The header is malformed or breaks a limit.
     *
     * @param reason why, safe to show to the client
     */
    record Invalid(String reason) implements KeyParseResult {}
}
