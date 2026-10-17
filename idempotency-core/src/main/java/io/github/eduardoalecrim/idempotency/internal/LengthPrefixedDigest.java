// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 over fields that are each preceded by their length, so that no two field sequences produce the same input.
 */
public final class LengthPrefixedDigest {

    private final MessageDigest digest;

    /** Starts an empty digest. */
    public LengthPrefixedDigest() {
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must support SHA-256.
            throw new IllegalStateException(e);
        }
    }

    /** Adds a UTF-8 string field. */
    public LengthPrefixedDigest add(String field) {
        return add(field.getBytes(StandardCharsets.UTF_8));
    }

    /** Adds a byte field. */
    public LengthPrefixedDigest add(byte[] field) {
        add(field.length);
        digest.update(field);
        return this;
    }

    /** Adds a count, as four big-endian bytes. */
    public LengthPrefixedDigest add(int count) {
        digest.update((byte) (count >>> 24));
        digest.update((byte) (count >>> 16));
        digest.update((byte) (count >>> 8));
        digest.update((byte) count);
        return this;
    }

    /** Returns the digest as 64 lowercase hex characters. */
    public String hex() {
        return Hex.encode(digest.digest());
    }
}
