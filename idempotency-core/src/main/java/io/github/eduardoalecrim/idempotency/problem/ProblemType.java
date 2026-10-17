// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.problem;

/** The problems the library answers with. Each slug resolves to a page under the problem base URI. */
public enum ProblemType {
    /** A required operation received no key. */
    MISSING_KEY("missing-key", 400, "Idempotency key required"),
    /** The key is malformed or breaks a limit. */
    INVALID_KEY("invalid-key", 400, "Invalid idempotency key"),
    /** The request body is larger than the configured limit. */
    REQUEST_TOO_LARGE("request-too-large", 413, "Request too large for idempotent processing"),
    /** An earlier request with the same key still holds its lease. */
    REQUEST_IN_PROGRESS("request-in-progress", 409, "Request with this idempotency key is in progress"),
    /** The key was used before with a different request. */
    KEY_REUSE("key-reuse", 422, "Idempotency key reused with a different request"),
    /** The store failed or timed out. */
    STORE_UNAVAILABLE("store-unavailable", 503, "Idempotency store unavailable");

    private final String slug;
    private final int status;
    private final String title;

    ProblemType(String slug, int status, String title) {
        this.slug = slug;
        this.status = status;
        this.title = title;
    }

    /** Returns the last segment of the type URI. */
    public String slug() {
        return slug;
    }

    /** Returns the HTTP status. */
    public int status() {
        return status;
    }

    /** Returns the title. */
    public String title() {
        return title;
    }
}
