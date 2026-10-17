// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

/**
 * Reads the start of the body for the engine, then serves those bytes again followed by the rest of the stream.
 *
 * <p>The container parses form parameters from its own stream, which this wrapper has already read, so form
 * parameters are parsed here from the query string and the buffered body.
 */
final class CachedBodyRequest extends HttpServletRequestWrapper {

    private byte @Nullable [] buffered;
    private @Nullable ServletInputStream stream;
    private @Nullable BufferedReader reader;
    private @Nullable Map<String, String[]> formParameters;

    CachedBodyRequest(HttpServletRequest request) {
        super(request);
    }

    /** Reads at most {@code maxBytes + 1} bytes, once. */
    byte[] body(int maxBytes) {
        if (buffered == null) {
            try {
                buffered = super.getInputStream().readNBytes(maxBytes + 1);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return buffered;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        byte[] head = buffered;
        if (head == null) {
            return super.getInputStream();
        }
        if (stream == null) {
            stream = new ReplayingInputStream(head, super.getInputStream());
        }
        return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        if (buffered == null) {
            return super.getReader();
        }
        if (reader == null) {
            reader = new BufferedReader(new InputStreamReader(getInputStream(), charset()));
        }
        return reader;
    }

    @Override
    public @Nullable String getParameter(String name) {
        String[] values = parameters().get(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return Collections.unmodifiableMap(parameters());
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters().keySet());
    }

    @Override
    public String @Nullable [] getParameterValues(String name) {
        return parameters().get(name);
    }

    private Map<String, String[]> parameters() {
        byte[] body = buffered;
        if (body == null || !isForm()) {
            return super.getParameterMap();
        }
        if (formParameters == null) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            parse(getQueryString(), StandardCharsets.UTF_8, values);
            parse(new String(body, charset()), charset(), values);
            Map<String, String[]> parameters = new LinkedHashMap<>();
            values.forEach((name, list) -> parameters.put(name, list.toArray(new String[0])));
            formParameters = parameters;
        }
        return formParameters;
    }

    private boolean isForm() {
        String contentType = getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.includes(MediaType.parseMediaType(contentType));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        try {
            return encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    private static void parse(@Nullable String encoded, Charset charset, Map<String, List<String>> values) {
        if (encoded == null || encoded.isEmpty()) {
            return;
        }
        for (String pair : StringUtils.delimitedListToStringArray(encoded, "&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String name = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), charset);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), charset);
            values.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
        }
    }

    /** The buffered bytes, then the rest of the original stream. */
    private static final class ReplayingInputStream extends ServletInputStream {

        private final ByteArrayInputStream head;
        private final ServletInputStream rest;

        ReplayingInputStream(byte[] head, ServletInputStream rest) {
            this.head = new ByteArrayInputStream(head);
            this.rest = rest;
        }

        @Override
        public int read() throws IOException {
            int b = head.read();
            return b >= 0 ? b : rest.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int n = head.read(b, off, len);
            return n > 0 ? n : rest.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return head.available() == 0 && rest.isFinished();
        }

        @Override
        public boolean isReady() {
            return head.available() > 0 || rest.isReady();
        }

        // Asynchronous handlers are rejected at startup (FR-20), so non-blocking reads never start here.
        @Override
        public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("Non-blocking reads are not supported on idempotent operations");
        }
    }
}
