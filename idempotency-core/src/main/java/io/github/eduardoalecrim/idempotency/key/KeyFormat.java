// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

/** Which key values are accepted once the header is parsed. */
public enum KeyFormat {
    /** Any non-empty value within the length limit. */
    ANY,
    /** Only a UUID in the canonical 8-4-4-4-12 hex form of RFC 9562. */
    UUID
}
