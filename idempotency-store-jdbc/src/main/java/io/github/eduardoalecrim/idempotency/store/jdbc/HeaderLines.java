// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.store.jdbc;

import io.github.eduardoalecrim.idempotency.spi.StoredResponse.Header;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Stored headers as HTTP/1.1 field lines, {@code Name: value} separated by CRLF (ADR 0016). */
final class HeaderLines {

    private HeaderLines() {}

    static String encode(List<Header> headers) {
        StringBuilder out = new StringBuilder();
        for (Header header : headers) {
            if (out.length() > 0) {
                out.append("\r\n");
            }
            out.append(header.name()).append(": ").append(header.value());
        }
        return out.toString();
    }

    // Oracle reads an empty string back as NULL.
    static List<Header> decode(@Nullable String lines) {
        List<Header> headers = new ArrayList<>();
        if (lines == null || lines.isEmpty()) {
            return headers;
        }
        for (String line : lines.split("\r\n", -1)) {
            int colon = line.indexOf(": ");
            if (colon < 0) {
                throw new IllegalArgumentException("stored header line has no ': ' separator");
            }
            headers.add(new Header(line.substring(0, colon), line.substring(colon + 2)));
        }
        return headers;
    }
}
