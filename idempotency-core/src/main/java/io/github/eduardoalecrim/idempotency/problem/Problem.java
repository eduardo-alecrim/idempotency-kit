// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.problem;

import io.github.eduardoalecrim.idempotency.internal.json.JsonStrings;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * An RFC 9457 problem details object.
 *
 * <p>It never carries fingerprints, bodies or the client identity.
 *
 * @param type the absolute type URI
 * @param title the summary of the problem type
 * @param status the HTTP status
 * @param detail what happened to this request and what to do
 * @param instance the request path
 * @param idempotencyKey the received key, when it could be parsed
 */
public record Problem(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        @Nullable String idempotencyKey) {

    /** Media type of the serialized problem. */
    public static final String MEDIA_TYPE = "application/problem+json";

    /** @throws IllegalArgumentException if the type URI is relative or the status is not an error status */
    public Problem {
        if (!type.isAbsolute()) {
            throw new IllegalArgumentException("problem type must be an absolute URI: " + type);
        }
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("problem status must be 4xx or 5xx: " + status);
        }
    }

    /** Returns the problem as UTF-8 JSON. */
    public byte[] toJson() {
        StringBuilder out = new StringBuilder(256).append("{\"type\":");
        JsonStrings.quote(type.toString(), out);
        out.append(",\"title\":");
        JsonStrings.quote(title, out);
        out.append(",\"status\":").append(status).append(",\"detail\":");
        JsonStrings.quote(detail, out);
        out.append(",\"instance\":");
        JsonStrings.quote(instance, out);
        if (idempotencyKey != null) {
            out.append(",\"idempotencyKey\":");
            JsonStrings.quote(idempotencyKey, out);
        }
        return out.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }
}
