// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

/** Which header syntax is accepted. */
public enum KeyParsing {
    /** Only an RFC 9651 String, such as {@code "4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12"}. */
    STRICT,
    /** Also an unquoted value of the characters {@code A-Z a-z 0-9 - _ . ~ + / = :}, as many clients send UUIDs. */
    LENIENT
}
