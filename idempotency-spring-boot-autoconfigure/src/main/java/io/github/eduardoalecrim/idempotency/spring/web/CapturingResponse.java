// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import io.github.eduardoalecrim.idempotency.CapturedResponse;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Buffers the handler's body up to the response limit, so the record is completed before the client sees the
 * response. A larger body is written through to the client as it comes, and is reported as truncated. Status and
 * headers go to the real response directly.
 */
final class CapturingResponse extends HttpServletResponseWrapper {

    private final int limit;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean overflowed;
    private boolean errorSent;
    private @Nullable ServletOutputStream stream;
    private @Nullable PrintWriter writer;

    CapturingResponse(HttpServletResponse response, int limit) {
        super(response);
        this.limit = limit;
    }

    @Override
    public ServletOutputStream getOutputStream() {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called");
        }
        if (stream == null) {
            stream = new CapturingStream();
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() {
        if (writer == null) {
            if (stream != null) {
                throw new IllegalStateException("getOutputStream() has already been called");
            }
            stream = new CapturingStream();
            writer = new PrintWriter(new OutputStreamWriter(stream, charset()));
        }
        return writer;
    }

    // Committing would send the response before its record is completed.
    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (overflowed) {
            super.flushBuffer();
        }
    }

    @Override
    public boolean isCommitted() {
        return overflowed && super.isCommitted();
    }

    @Override
    public void resetBuffer() {
        if (!overflowed) {
            buffer.reset();
        }
        super.resetBuffer();
    }

    @Override
    public void reset() {
        if (!overflowed) {
            buffer.reset();
        }
        super.reset();
    }

    @Override
    public void sendError(int sc, @Nullable String msg) throws IOException {
        errorSent = true;
        super.sendError(sc, msg);
    }

    @Override
    public void sendError(int sc) throws IOException {
        errorSent = true;
        super.sendError(sc);
    }

    /** Whether the handler used {@code sendError}, whose body the container renders after the filter returns. */
    boolean errorSent() {
        return errorSent;
    }

    /** Returns the status, headers and buffered body. */
    CapturedResponse captured() {
        if (writer != null) {
            writer.flush();
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : getHeaderNames()) {
            headers.putIfAbsent(name, new ArrayList<>(getHeaders(name)));
        }
        return new CapturedResponse(getStatus(), headers, buffer.toByteArray(), overflowed);
    }

    /** Writes the buffered body to the client. */
    void sendBody() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        if (!overflowed && !errorSent && buffer.size() > 0) {
            if (!getResponse().isCommitted() && !containsHeader("Content-Length")) {
                super.setContentLength(buffer.size());
            }
            buffer.writeTo(super.getOutputStream());
        }
        if (!errorSent) {
            super.flushBuffer();
        }
    }

    private Charset charset() {
        String encoding = getCharacterEncoding();
        try {
            return encoding != null ? Charset.forName(encoding) : StandardCharsets.ISO_8859_1;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.ISO_8859_1;
        }
    }

    private final class CapturingStream extends ServletOutputStream {

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (!overflowed && buffer.size() + len > limit) {
                overflowed = true;
                buffer.writeTo(CapturingResponse.super.getOutputStream());
            }
            if (overflowed) {
                CapturingResponse.super.getOutputStream().write(b, off, len);
            } else {
                buffer.write(b, off, len);
            }
        }

        @Override
        public void flush() throws IOException {
            if (overflowed) {
                CapturingResponse.super.getOutputStream().flush();
            }
        }

        @Override
        public boolean isReady() {
            return true;
        }

        // Asynchronous handlers are rejected at startup (FR-20), so non-blocking writes never start here.
        @Override
        public void setWriteListener(WriteListener listener) {
            throw new IllegalStateException("Non-blocking writes are not supported on idempotent operations");
        }
    }
}
