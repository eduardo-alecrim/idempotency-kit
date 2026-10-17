// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.fingerprint;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.eduardoalecrim.idempotency.fingerprint.FingerprintInput.Header;
import io.github.eduardoalecrim.idempotency.spi.Fingerprint;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultFingerprintStrategyTest {

    private static final String JSON = "application/json";
    private static final String BODY = "{\"amount\":10.50,\"currency\":\"EUR\",\"items\":[1,2]}";

    private final FingerprintStrategy strategy = new FingerprintStrategy.Default();

    @Test
    @DisplayName("FR-14: key order, whitespace and number form do not change the fingerprint of a JSON body")
    void ignoresJsonFormatting() {
        Fingerprint original = fingerprint("POST", "/orders", JSON, List.of(), BODY);

        assertThat(fingerprint(
                        "POST",
                        "/orders",
                        JSON,
                        List.of(),
                        "{ \"items\" : [ 1, 2 ],\n \"currency\":\"EUR\", \"amount\": 1.05e1 }"))
                .isEqualTo(original);
        assertThat(fingerprint("POST", "/orders", "Application/JSON; charset=utf-8", List.of(), BODY))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("FR-14: a changed value, route, method, media type or header changes the fingerprint")
    void changesWithEveryPart() {
        Fingerprint original =
                fingerprint("POST", "/orders", JSON, List.of(new Header("X-Tenant", List.of("a"))), BODY);

        assertThat(original)
                .isNotEqualTo(fingerprint(
                        "POST",
                        "/orders",
                        JSON,
                        List.of(new Header("X-Tenant", List.of("a"))),
                        BODY.replace("EUR", "USD")))
                .isNotEqualTo(
                        fingerprint("POST", "/payments", JSON, List.of(new Header("X-Tenant", List.of("a"))), BODY))
                .isNotEqualTo(
                        fingerprint("PATCH", "/orders", JSON, List.of(new Header("X-Tenant", List.of("a"))), BODY))
                .isNotEqualTo(fingerprint(
                        "POST",
                        "/orders",
                        "application/merge-patch+json",
                        List.of(new Header("X-Tenant", List.of("a"))),
                        BODY))
                .isNotEqualTo(fingerprint("POST", "/orders", JSON, List.of(new Header("X-Tenant", List.of("b"))), BODY))
                .isNotEqualTo(fingerprint("POST", "/orders", JSON, List.of(new Header("X-Tenant", List.of())), BODY))
                .isNotEqualTo(fingerprint("POST", "/orders", JSON, List.of(), BODY));
    }

    @Test
    @DisplayName("FR-14: header names are case-insensitive and values are trimmed")
    void normalizesHeaders() {
        assertThat(fingerprint("POST", "/orders", JSON, List.of(new Header("X-Tenant", List.of(" a "))), BODY))
                .isEqualTo(fingerprint("POST", "/orders", JSON, List.of(new Header("x-tenant", List.of("a"))), BODY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/problem+json", "application/vnd.api+json"})
    void canonicalizesStructuredJsonMediaTypes(String mediaType) {
        assertThat(fingerprint("POST", "/a", mediaType, List.of(), "{\"b\":1,\"a\":2}"))
                .isEqualTo(fingerprint("POST", "/a", mediaType, List.of(), "{\"a\":2, \"b\":1}"));
    }

    @Test
    void hashesOtherBodiesAsRawBytes() {
        assertThat(fingerprint("POST", "/a", "text/plain", List.of(), "{\"b\":1,\"a\":2}"))
                .isNotEqualTo(fingerprint("POST", "/a", "text/plain", List.of(), "{\"a\":2,\"b\":1}"));
        assertThat(fingerprint("POST", "/a", null, List.of(), "x"))
                .isNotEqualTo(fingerprint("POST", "/a", null, List.of(), "y"));
        assertThat(fingerprint("POST", "/a", "+json", List.of(), "{\"b\":1,\"a\":2}"))
                .isNotEqualTo(fingerprint("POST", "/a", "+json", List.of(), "{\"a\":2,\"b\":1}"));
    }

    @Test
    void hashesInvalidJsonAsRawBytes() {
        assertThat(fingerprint("POST", "/a", JSON, List.of(), "{\"a\":"))
                .isEqualTo(fingerprint("POST", "/a", JSON, List.of(), "{\"a\":"))
                .isNotEqualTo(fingerprint("POST", "/a", JSON, List.of(), "{\"a\": "));
    }

    @Test
    void canonicalizationCanBeTurnedOff() {
        FingerprintStrategy raw = new FingerprintStrategy.Default(false);

        assertThat(raw.fingerprint(input("POST", "/a", JSON, List.of(), "{\"b\":1,\"a\":2}")))
                .isNotEqualTo(raw.fingerprint(input("POST", "/a", JSON, List.of(), "{\"a\":2,\"b\":1}")));
    }

    @Test
    void inputCopiesItsBodyAndHidesItFromToString() {
        byte[] body = "secret".getBytes(UTF_8);
        FingerprintInput input = new FingerprintInput("POST", "/a", null, List.of(), body);
        body[0] = 'x';

        assertThat(input.body()).isEqualTo("secret".getBytes(UTF_8));
        assertThat(input).isEqualTo(new FingerprintInput("POST", "/a", null, List.of(), "secret".getBytes(UTF_8)));
        assertThat(input)
                .hasSameHashCodeAs(new FingerprintInput("POST", "/a", null, List.of(), "secret".getBytes(UTF_8)));
        assertThat(input).isNotEqualTo(new FingerprintInput("POST", "/a", JSON, List.of(), "secret".getBytes(UTF_8)));
        assertThat(input).isNotEqualTo("secret");
        assertThat(input.toString()).doesNotContain("secret");
        assertThat(input.mediaType()).isEmpty();
    }

    private Fingerprint fingerprint(
            String method, String route, @Nullable String contentType, List<Header> headers, String body) {
        return strategy.fingerprint(input(method, route, contentType, headers, body));
    }

    private static FingerprintInput input(
            String method, String route, @Nullable String contentType, List<Header> headers, String body) {
        return new FingerprintInput(method, route, contentType, headers, body.getBytes(UTF_8));
    }
}
