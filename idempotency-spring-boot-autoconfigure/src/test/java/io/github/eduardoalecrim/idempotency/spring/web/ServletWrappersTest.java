// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spring.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.github.eduardoalecrim.idempotency.CapturedResponse;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties;
import io.github.eduardoalecrim.idempotency.spring.IdempotencyProperties.ClientIdentity.Source;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServletWrappersTest {

    @Test
    void cachedBodyServesTheBufferedBytesThenTheRest() throws Exception {
        CachedBodyRequest request = new CachedBodyRequest(request("0123456789"));

        assertThat(request.body(3)).isEqualTo("0123".getBytes(UTF_8));
        assertThat(request.body(100)).isEqualTo("0123".getBytes(UTF_8));
        assertThat(request.getInputStream().readAllBytes()).isEqualTo("0123456789".getBytes(UTF_8));
        assertThat(request.getInputStream().isFinished()).isTrue();
    }

    @Test
    void cachedBodyReadsTextInTheRequestEncoding() throws Exception {
        MockHttpServletRequest mock = request("olá");
        mock.setCharacterEncoding("UTF-8");
        CachedBodyRequest request = new CachedBodyRequest(mock);
        request.body(1024);

        BufferedReader reader = request.getReader();

        assertThat(reader.readLine()).isEqualTo("olá");
        assertThat(request.getReader()).isSameAs(reader);
    }

    @Test
    void cachedBodyLeavesTheStreamAloneUntilRead() throws Exception {
        CachedBodyRequest request = new CachedBodyRequest(request("abc"));

        assertThat(request.getInputStream().readAllBytes()).isEqualTo("abc".getBytes(UTF_8));
    }

    @Test
    void cachedBodyParsesFormParametersFromTheQueryAndTheBody() {
        MockHttpServletRequest mock = request("name=Ana+Maria&tag=a&tag=b&empty&=x");
        mock.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        mock.setQueryString("page=2");
        CachedBodyRequest request = new CachedBodyRequest(mock);
        request.body(1024);

        assertThat(request.getParameter("name")).isEqualTo("Ana Maria");
        assertThat(request.getParameter("page")).isEqualTo("2");
        assertThat(request.getParameterValues("tag")).containsExactly("a", "b");
        assertThat(request.getParameter("empty")).isEmpty();
        assertThat(request.getParameter("missing")).isNull();
        assertThat(request.getParameterMap()).containsKeys("name", "page", "tag", "empty");
        assertThat(Collections.list(request.getParameterNames())).contains("name", "page");
    }

    @Test
    void cachedBodyKeepsContainerParametersForOtherContent() {
        MockHttpServletRequest mock = request("{}");
        mock.setContentType("application/json");
        mock.addParameter("q", "1");
        CachedBodyRequest request = new CachedBodyRequest(mock);
        request.body(1024);

        assertThat(request.getParameter("q")).isEqualTo("1");
    }

    @Test
    void capturingResponseBuffersUntilSent() throws Exception {
        MockHttpServletResponse mock = new MockHttpServletResponse();
        CapturingResponse response = new CapturingResponse(mock, 100);
        response.setStatus(201);
        response.setHeader("Location", "/orders/1");
        response.getOutputStream().write("hello".getBytes(UTF_8));
        response.flushBuffer();

        assertThat(mock.isCommitted()).isFalse();
        assertThat(response.isCommitted()).isFalse();
        CapturedResponse captured = response.captured();
        assertThat(captured.status()).isEqualTo(201);
        assertThat(captured.headers()).containsEntry("Location", List.of("/orders/1"));
        assertThat(captured.body()).isEqualTo("hello".getBytes(UTF_8));
        assertThat(captured.truncated()).isFalse();
        assertThat(mock.getContentAsString()).isEmpty();

        response.sendBody();

        assertThat(mock.getContentAsString()).isEqualTo("hello");
        assertThat(mock.getContentLength()).isEqualTo(5);
    }

    @Test
    void capturingResponseWritesThroughAfterTheLimit() throws Exception {
        MockHttpServletResponse mock = new MockHttpServletResponse();
        CapturingResponse response = new CapturingResponse(mock, 4);
        PrintWriter writer = response.getWriter();
        writer.write("abc");
        writer.flush();
        writer.write("defgh");
        response.flushBuffer();

        assertThat(response.captured().truncated()).isTrue();
        response.sendBody();
        assertThat(mock.getContentAsString()).isEqualTo("abcdefgh");
    }

    @Test
    void capturingResponseResetsItsBuffer() throws Exception {
        MockHttpServletResponse mock = new MockHttpServletResponse();
        CapturingResponse response = new CapturingResponse(mock, 100);
        response.getOutputStream().write('x');
        response.resetBuffer();
        response.getOutputStream().write('y');
        response.reset();
        response.getOutputStream().write('z');
        response.getOutputStream().flush();

        assertThat(response.captured().body()).isEqualTo("z".getBytes(UTF_8));
        assertThat(response.getOutputStream().isReady()).isTrue();
    }

    @Test
    void capturingResponseAllowsOneOutputKind() {
        CapturingResponse streamFirst = new CapturingResponse(new MockHttpServletResponse(), 10);
        streamFirst.getOutputStream();
        assertThatIllegalStateException().isThrownBy(streamFirst::getWriter);

        CapturingResponse writerFirst = new CapturingResponse(new MockHttpServletResponse(), 10);
        writerFirst.getWriter();
        assertThatIllegalStateException().isThrownBy(writerFirst::getOutputStream);
        assertThat(writerFirst.getWriter()).isNotNull();
    }

    @Test
    void capturingResponseNotesSendError() throws Exception {
        MockHttpServletResponse mock = new MockHttpServletResponse();
        CapturingResponse response = new CapturingResponse(mock, 10);

        response.sendError(400, "bad");

        assertThat(response.errorSent()).isTrue();
        response.sendBody();
        assertThat(mock.getStatus()).isEqualTo(400);

        CapturingResponse plain = new CapturingResponse(new MockHttpServletResponse(), 10);
        plain.sendError(404);
        assertThat(plain.errorSent()).isTrue();
    }

    @Test
    void clientIdentityComesFromThePrincipalAHeaderOrNowhere() {
        MockHttpServletRequest request = request("");
        request.setUserPrincipal(() -> "alice");
        request.addHeader("X-Client", " tenant-1 ");
        IdempotencyProperties.ClientIdentity properties = new IdempotencyProperties.ClientIdentity();

        assertThat(ServletClientIdentity.from(properties).resolve(request)).hasValue("alice");
        properties.setSource(Source.HEADER);
        properties.setHeader("X-Client");
        assertThat(ServletClientIdentity.from(properties).resolve(request)).hasValue("tenant-1");
        properties.setSource(Source.NONE);
        assertThat(ServletClientIdentity.from(properties).resolve(request)).isEmpty();
    }

    private static MockHttpServletRequest request(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders");
        request.setContent(body.getBytes(UTF_8));
        return request;
    }
}
