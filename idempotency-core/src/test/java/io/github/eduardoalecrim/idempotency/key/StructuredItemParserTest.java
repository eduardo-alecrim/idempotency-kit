// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

// String cases from the httpwg structured-field-tests suite (string.json), plus parameter cases.
@DisplayName("FR-01: RFC 9651 String Items")
class StructuredItemParserTest {

    static Stream<Arguments> validStrings() {
        return Stream.of(
                Arguments.of("\"foo bar\"", "foo bar"),
                Arguments.of("\"\"", ""),
                Arguments.of("\"" + "foo ".repeat(1024) + "\"", "foo ".repeat(1024)),
                Arguments.of("\"   \"", "   "),
                Arguments.of("\"foo \\\"bar\\\" \\\\ baz\"", "foo \"bar\" \\ baz"),
                Arguments.of("  \"foo\"  ", "foo"));
    }

    @ParameterizedTest
    @MethodSource("validStrings")
    void parsesStrings(String input, String expected) {
        assertThat(StructuredItemParser.parseStringItem(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"füü\"",
                "\"\t\"",
                "\" \n \"",
                "'foo'",
                "\"foo",
                "\"foo \\,\"",
                "\"foo \\\"",
                "\"foo \\",
                "foo",
                "123",
                "",
                "\"a\",",
                "\"a\" \"b\"",
                "\"a\"x"
            })
    void rejectsMalformedStrings(String input) {
        assertThat(StructuredItemParser.parseStringItem(input)).isNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"abc\";a=1;b=2",
                "\"abc\"; a=1",
                "\"abc\";a",
                "\"abc\";*a.b_c-d*=-12",
                "\"abc\";a=1.5",
                "\"abc\";a=123456789012.123",
                "\"abc\";a=123456789012345",
                "\"abc\";a=\"x \\\" y\"",
                "\"abc\";a=tok/en:x",
                "\"abc\";a=*tok",
                "\"abc\";a=:aGVsbG8=:",
                "\"abc\";a=?0;b=?1",
                "\"abc\";a=@1659578233",
                "\"abc\";a=%\"f%c3%bc\""
            })
    void ignoresValidParameters(String input) {
        assertThat(StructuredItemParser.parseStringItem(input)).isEqualTo("abc");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"abc\" ;a=1",
                "\"abc\";A=1",
                "\"abc\";1a=1",
                "\"abc\";",
                "\"abc\";a=",
                "\"abc\";a=1.5555",
                "\"abc\";a=1.",
                "\"abc\";a=1234567890123.1",
                "\"abc\";a=1234567890123456",
                "\"abc\";a=-",
                "\"abc\";a=-x",
                "\"abc\";a=\"x",
                "\"abc\";a=:aGVs",
                "\"abc\";a=:a!:",
                "\"abc\";a=?2",
                "\"abc\";a=@1.5",
                "\"abc\";a=%x",
                "\"abc\";a=%\"%C3%BC\"",
                "\"abc\";a=%\"%c3\"",
                "\"abc\";a=%\"%c\"",
                "\"abc\";a=%\"ü\"",
                "\"abc\";a=%\"open",
                "\"abc\";a=(1)"
            })
    void rejectsMalformedParameters(String input) {
        assertThat(StructuredItemParser.parseStringItem(input)).isNull();
    }
}
