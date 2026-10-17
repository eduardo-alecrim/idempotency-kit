// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.fingerprint;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The parts of a request a {@link FingerprintStrategy} may use.
 *
 * @param method the HTTP method, in uppercase
 * @param routeTemplate the route template of the operation
 * @param contentType the {@code Content-Type} header, if any
 * @param headers the selected headers, in the configured order; absent headers have no values
 * @param body the buffered request body
 */
@SuppressWarnings("ArrayRecordComponent") // the array is copied in and out, and equals compares contents
public record FingerprintInput(
        String method, String routeTemplate, @Nullable String contentType, List<Header> headers, byte[] body) {

    /** Copies the headers and the body. */
    public FingerprintInput {
        headers = List.copyOf(headers);
        body = body.clone();
    }

    /** Returns a copy of the body. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** Returns {@code type/subtype} in lowercase without parameters, or an empty string without a content type. */
    public String mediaType() {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String type = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return type.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof FingerprintInput other
                && method.equals(other.method)
                && routeTemplate.equals(other.routeTemplate)
                && Objects.equals(contentType, other.contentType)
                && headers.equals(other.headers)
                && Arrays.equals(body, other.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, routeTemplate, contentType, headers, Arrays.hashCode(body));
    }

    // Bodies and header values stay out of toString so that they do not end up in logs.
    @Override
    public String toString() {
        return "FingerprintInput[method=" + method + ", routeTemplate=" + routeTemplate + ", bodyLength=" + body.length
                + "]";
    }

    /**
     * A selected request header.
     *
     * @param name the header name, in lowercase
     * @param values the trimmed values, in request order
     */
    public record Header(String name, List<String> values) {

        /** Lowercases the name, trims and copies the values. */
        public Header {
            name = name.toLowerCase(Locale.ROOT);
            values = values.stream().map(String::trim).toList();
        }
    }
}
