// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A request to an idempotent operation, as an adapter presents it to the engine. */
public interface IncomingRequest {

    /** Returns the HTTP method, in uppercase. */
    String method();

    /** Returns the request path without the query, used as the problem {@code instance}. */
    String path();

    /** Returns the route template of the operation. */
    String routeTemplate();

    /** Returns the values of a header, matched case-insensitively; empty when absent. */
    List<String> headerValues(String name);

    /** Returns the {@code Content-Type} header, if any. */
    @Nullable
    String contentType();

    /** Returns the client identity, or empty for the anonymous client. */
    Optional<String> clientIdentity();

    /**
     * Reads the body, stopping after {@code maxBytes + 1} bytes so that a larger body is detected without buffering it.
     * The adapter must still deliver the whole body to the handler.
     *
     * @throws java.io.UncheckedIOException if reading fails
     */
    byte[] body(int maxBytes);
}
