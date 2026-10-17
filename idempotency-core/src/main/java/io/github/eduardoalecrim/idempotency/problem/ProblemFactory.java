// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.problem;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/** Builds the library's problems under a base URI. */
public final class ProblemFactory {

    /** Base of the type URIs: the problem pages of the documentation site. */
    public static final URI DEFAULT_BASE_URI =
            URI.create("https://eduardo-alecrim.github.io/idempotency-kit/problems/");

    private final URI baseUri;
    private final String headerName;

    /**
     * @param baseUri absolute URI ending with {@code /}, under which each problem slug resolves
     * @param headerName the request header carrying the key, named in details
     * @throws IllegalArgumentException if the base URI is relative or does not end with {@code /}
     */
    public ProblemFactory(URI baseUri, String headerName) {
        if (!baseUri.isAbsolute() || !baseUri.getPath().endsWith("/")) {
            throw new IllegalArgumentException("problem base URI must be absolute and end with '/': " + baseUri);
        }
        this.baseUri = baseUri;
        this.headerName = headerName;
    }

    /** Returns a factory with the default base URI and header name. */
    public static ProblemFactory defaults() {
        return new ProblemFactory(DEFAULT_BASE_URI, "Idempotency-Key");
    }

    /** The operation requires a key and the request has none. */
    public Problem missingKey(String instance) {
        return create(
                ProblemType.MISSING_KEY,
                "This operation requires an " + headerName + " header. Send a new unique key with the request.",
                instance,
                null);
    }

    /**
     * The key is malformed or breaks a limit.
     *
     * @param reason the parser's explanation, which never echoes the raw value
     */
    public Problem invalidKey(String instance, String reason) {
        return create(ProblemType.INVALID_KEY, "The " + headerName + " header is invalid. " + reason, instance, null);
    }

    /** The request body is over the limit. */
    public Problem requestTooLarge(String instance, long maxBytes, String idempotencyKey) {
        return create(
                ProblemType.REQUEST_TOO_LARGE,
                "Idempotent requests to this operation accept bodies of at most " + maxBytes + " bytes.",
                instance,
                idempotencyKey);
    }

    /** An earlier request with the key is still running. */
    public Problem requestInProgress(String instance, String idempotencyKey) {
        return create(
                ProblemType.REQUEST_IN_PROGRESS,
                "A request with this key is still being processed. Retry after the time in the Retry-After header.",
                instance,
                idempotencyKey);
    }

    /** The key was used with a different request. */
    public Problem keyReuse(String instance, String idempotencyKey) {
        return create(
                ProblemType.KEY_REUSE,
                "This key was already used with a different request. Send a new key for a new request.",
                instance,
                idempotencyKey);
    }

    /** The store failed. */
    public Problem storeUnavailable(String instance, @Nullable String idempotencyKey) {
        return create(
                ProblemType.STORE_UNAVAILABLE,
                "The request could not be checked for duplicates and was not processed. Retry with the same key.",
                instance,
                idempotencyKey);
    }

    private Problem create(ProblemType type, String detail, String instance, @Nullable String idempotencyKey) {
        return new Problem(baseUri.resolve(type.slug()), type.title(), type.status(), detail, instance, idempotencyKey);
    }
}
