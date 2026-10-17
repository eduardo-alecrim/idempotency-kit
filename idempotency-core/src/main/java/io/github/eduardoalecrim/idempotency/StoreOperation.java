// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

/** A store call, as reported to observers. */
public enum StoreOperation {
    /** {@code claim}. */
    CLAIM,
    /** {@code complete}. */
    COMPLETE,
    /** {@code release}. */
    RELEASE,
    /** {@code find}. */
    FIND,
    /** {@code purgeExpired}. */
    PURGE
}
