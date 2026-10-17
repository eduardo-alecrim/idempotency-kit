// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

/** Why a handler runs without protection. */
public enum BypassReason {
    /** An optional operation received no key. */
    NO_KEY,
    /** The store failed under fail-open. */
    FAIL_OPEN
}
