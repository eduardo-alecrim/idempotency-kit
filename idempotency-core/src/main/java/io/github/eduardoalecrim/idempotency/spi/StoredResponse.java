// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Status, allowlisted headers and body kept in a completed record.
 *
 * @param status the HTTP status
 * @param headers the headers restored on replay, in order
 * @param body the response body; empty when omitted
 * @param bodyOmitted whether the body was too large to store
 */
@SuppressWarnings("ArrayRecordComponent") // the array is copied in and out, and equals compares contents
public record StoredResponse(int status, List<Header> headers, byte[] body, boolean bodyOmitted) {

    /**
     * @throws IllegalArgumentException if the status is outside 100–599, or the body is not empty although omitted
     */
    public StoredResponse {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status out of range: " + status);
        }
        if (bodyOmitted && body.length > 0) {
            throw new IllegalArgumentException("an omitted body must be empty");
        }
        headers = List.copyOf(headers);
        body = body.clone();
    }

    /** Returns a copy of the body. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Returns the body length without copying it. */
    public int bodyLength() {
        return body.length;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof StoredResponse other
                && status == other.status
                && bodyOmitted == other.bodyOmitted
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        int result = Integer.hashCode(status);
        result = 31 * result + headers.hashCode();
        result = 31 * result + Arrays.hashCode(body);
        return 31 * result + Boolean.hashCode(bodyOmitted);
    }

    // Bodies stay out of toString so that they do not end up in logs.
    @Override
    public String toString() {
        return "StoredResponse[status=" + status + ", headers=" + headers + ", bodyLength=" + body.length
                + ", bodyOmitted=" + bodyOmitted + "]";
    }

    /**
     * One response header field.
     *
     * @param name the field name, as sent by the handler
     * @param value the field value, without CR or LF
     */
    public record Header(String name, String value) {

        private static final Pattern TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

        /** @throws IllegalArgumentException if the name is not an HTTP token or the value contains CR, LF or NUL */
        public Header {
            if (!TOKEN.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid header name: " + name);
            }
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\r' || c == '\n' || c == 0) {
                    throw new IllegalArgumentException("header value of " + name + " contains CR, LF or NUL");
                }
            }
        }
    }
}
