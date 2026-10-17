// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class JsonCanonicalizerTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                " [ ] | []",
                "-0 | 0",
                "1E2 | 100",
                "-1.50e-1 | -0.15",
                "\"\\u001f\\/\" | \"\\u001f/\"",
                "\"\\b\\f\\n\\r\\t\\\"\\\\\" | \"\\b\\f\\n\\r\\t\\\"\\\\\"",
                "\"\\u00E9\" | \"é\"",
                "{\"b\":[true,false,null],\"a\":{}} | {\"a\":{},\"b\":[true,false,null]}",
                "1e-400 | 0"
            })
    void canonicalizes(String input, String expected) {
        assertThat(canonical(input)).hasValue(expected);
    }

    @Test
    void keepsSurrogatePairs() {
        assertThat(canonical("\"\\ud83d\\ude02\"")).hasValue("\"\uD83D\uDE02\"");
    }

    @Test
    void acceptsNestingUpToTheLimit() {
        assertThat(canonical("[".repeat(256) + "]".repeat(256))).isPresent();
        assertThat(canonical("[".repeat(257) + "]".repeat(257))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "{",
                "[1,]",
                "[1 2]",
                "{\"a\" 1}",
                "{\"a\":1,}",
                "{1:2}",
                "{\"a\":1,\"a\":2}",
                "\"\\ud800\"",
                "\"\\udc00\"",
                "\"\\ud800x\"",
                "01",
                "1.",
                ".5",
                "+1",
                "1e",
                "1e+",
                "-",
                "tru",
                "nul",
                "falsey",
                "\"\u0001\"",
                "\"\\x\"",
                "\"\\u12\"",
                "\"\\u12g4\"",
                "\"\\u\u0660\u0660\u0660\u0660\"",
                "\"abc",
                "\"abc\\",
                "1e400",
                "\uFEFF{}",
                "[] []"
            })
    void reportsInvalidDocuments(String input) {
        assertThat(canonical(input)).isEmpty();
    }

    @Test
    void reportsInvalidUtf8() {
        assertThat(JsonCanonicalizer.canonicalize(new byte[] {'"', (byte) 0xC3, '"'}))
                .isEmpty();
    }

    private static Optional<String> canonical(String input) {
        return JsonCanonicalizer.canonicalize(input.getBytes(UTF_8)).map(bytes -> new String(bytes, UTF_8));
    }
}
