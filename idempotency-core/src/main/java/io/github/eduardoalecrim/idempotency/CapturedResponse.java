// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The handler's response, as an adapter captured it.
 *
 * @param status the HTTP status
 * @param headers the response headers, in order
 * @param body the captured body
 * @param truncated whether the body was longer than what the adapter captured
 */
@SuppressWarnings("ArrayRecordComponent") // the array is copied in and out, and equals compares contents
public record CapturedResponse(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {

    /** Copies the headers and the body. */
    public CapturedResponse {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        headers = Collections.unmodifiableMap(copy);
        body = body.clone();
    }

    /** Returns a copy of the body. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Returns the captured body length without copying it. */
    public int bodyLength() {
        return body.length;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof CapturedResponse other
                && status == other.status
                && truncated == other.truncated
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, headers, Arrays.hashCode(body), truncated);
    }

    // Bodies stay out of toString so that they do not end up in logs.
    @Override
    public String toString() {
        return "CapturedResponse[status=" + status + ", bodyLength=" + body.length + ", truncated=" + truncated + "]";
    }
}
