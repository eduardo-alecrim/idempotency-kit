// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.spi;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.eduardoalecrim.idempotency.spi.StoredResponse.Header;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoredResponseTest {

    private static final List<Header> HEADERS = List.of(new Header("Content-Type", "application/json"));

    @Test
    void copiesTheBodyInAndOut() {
        byte[] body = "{}".getBytes(UTF_8);
        StoredResponse response = new StoredResponse(201, HEADERS, body, false);

        body[0] = 'x';
        response.body()[0] = 'y';

        assertThat(response.body()).isEqualTo("{}".getBytes(UTF_8));
        assertThat(response.bodyLength()).isEqualTo(2);
    }

    @Test
    void copiesTheHeaders() {
        List<Header> headers = new ArrayList<>(HEADERS);
        StoredResponse response = new StoredResponse(201, headers, new byte[0], false);
        headers.clear();
        assertThat(response.headers()).hasSize(1);
    }

    @Test
    void comparesBodyContents() {
        StoredResponse a = new StoredResponse(200, HEADERS, "a".getBytes(UTF_8), false);
        StoredResponse b = new StoredResponse(200, HEADERS, "a".getBytes(UTF_8), false);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new StoredResponse(200, HEADERS, "b".getBytes(UTF_8), false));
        assertThat(a).isNotEqualTo(new StoredResponse(201, HEADERS, "a".getBytes(UTF_8), false));
        assertThat(a).isNotEqualTo(new StoredResponse(200, List.of(), "a".getBytes(UTF_8), false));
        assertThat(a).isNotEqualTo(new StoredResponse(200, HEADERS, new byte[0], true));
        assertThat(a).isNotEqualTo("a");
    }

    @Test
    void keepsTheBodyOutOfToString() {
        String text = new StoredResponse(200, HEADERS, "secret".getBytes(UTF_8), false).toString();
        assertThat(text).contains("status=200", "bodyLength=6").doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 99, 600})
    void rejectsStatusOutOfRange(int status) {
        assertThatIllegalArgumentException().isThrownBy(() -> new StoredResponse(status, HEADERS, new byte[0], false));
    }

    @Test
    void rejectsBodyWhenOmitted() {
        assertThatIllegalArgumentException().isThrownBy(() -> new StoredResponse(200, HEADERS, new byte[1], true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Content Type", "Content:Type", "Ünicode"})
    void rejectsInvalidHeaderNames(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Header(name, "v"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\rb", "a\nb", "a\u0000b"})
    void rejectsHeaderValuesThatBreakFieldLines(String value) {
        assertThatIllegalArgumentException().isThrownBy(() -> new Header("X-Test", value));
    }
}
