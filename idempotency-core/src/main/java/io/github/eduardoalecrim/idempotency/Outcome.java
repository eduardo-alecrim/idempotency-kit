// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

/** How the library handled one request. */
public enum Outcome {
    /** The handler ran and its response was completed. */
    EXECUTED,
    /** The stored response was sent instead of running the handler. */
    REPLAYED,
    /** An earlier request with the key still holds its lease; answered 409. */
    IN_PROGRESS,
    /** The key was used with a different request; answered 422. */
    KEY_REUSE,
    /** A required operation received no key; answered 400. */
    MISSING_KEY,
    /** The key was malformed or broke a limit; answered 400. */
    INVALID_KEY,
    /** The body was over the limit; answered 413. */
    REQUEST_TOO_LARGE,
    /** The handler ran and the record was released, so a retry runs again. */
    RELEASED,
    /** The store failed under fail-closed; answered 503. */
    STORE_UNAVAILABLE,
    /** An optional operation received no key; the handler ran without protection. */
    NO_KEY,
    /** The store failed under fail-open; the handler ran without protection. */
    FAIL_OPEN
}
