// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal;

/** Lowercase hexadecimal encoding. */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {}

    /** Encodes bytes as lowercase hex, two characters per byte. */
    public static String encode(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = DIGITS[(bytes[i] >> 4) & 0xF];
            out[i * 2 + 1] = DIGITS[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** Whether {@code value} has exactly {@code length} lowercase hex characters. */
    public static boolean isLowerHex(String value, int length) {
        if (value.length() != length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }
}
