// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyParserTest {

    private static final String UUID = "4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12";

    private final IdempotencyKeyParser strict = IdempotencyKeyParser.defaults();
    private final IdempotencyKeyParser lenient =
            new IdempotencyKeyParser(KeyParsing.LENIENT, IdempotencyKeyParser.DEFAULT_MAX_LENGTH, KeyFormat.ANY);

    @Test
    @DisplayName("FR-01: strict mode accepts a quoted key and ignores its parameters")
    void strictAcceptsQuotedKey() {
        assertThat(strict.parse("\"" + UUID + "\"")).isEqualTo(new KeyParseResult.Valid(UUID));
        assertThat(strict.parse(" \"a b\";v=1\t")).isEqualTo(new KeyParseResult.Valid("a b"));
    }

    @ParameterizedTest
    @ValueSource(strings = {UUID, "\"abc", "\"a\", \"b\"", "\"\""})
    @DisplayName("FR-01: strict mode rejects bare, malformed, repeated and empty keys")
    void strictRejectsOtherValues(String value) {
        assertThat(strict.parse(value)).isInstanceOf(KeyParseResult.Invalid.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {UUID, "AZaz09-_.~+/=:", "dGVzdA=="})
    @DisplayName("FR-01: lenient mode also accepts unquoted keys of the allowed characters")
    void lenientAcceptsBareKeys(String value) {
        assertThat(lenient.parse(value)).isEqualTo(new KeyParseResult.Valid(value));
    }

    @Test
    @DisplayName("FR-01: lenient mode still parses quoted keys and rejects other characters")
    void lenientParsesQuotedKeys() {
        assertThat(lenient.parse("\"a\\\"b\"")).isEqualTo(new KeyParseResult.Valid("a\"b"));
        assertThat(lenient.parse("a b")).isInstanceOf(KeyParseResult.Invalid.class);
        assertThat(lenient.parse("a,b")).isInstanceOf(KeyParseResult.Invalid.class);
    }

    @Test
    @DisplayName("FR-02: keys longer than the maximum length are rejected")
    void rejectsLongKeys() {
        IdempotencyKeyParser parser = new IdempotencyKeyParser(KeyParsing.STRICT, 5, KeyFormat.ANY);

        assertThat(parser.parse("\"12345\"")).isEqualTo(new KeyParseResult.Valid("12345"));
        assertThat(parser.parse("\"123456\""))
                .isEqualTo(new KeyParseResult.Invalid("The key must be at most 5 characters long."));
        // The limit applies after unescaping.
        assertThat(parser.parse("\"\\\\\\\\\\\\\\\\\\\\\"")).isEqualTo(new KeyParseResult.Valid("\\\\\\\\\\"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12\"", "\"4A1C7D9E-5F3B-4E8A-9C21-7B0D3E6F8A12\""})
    @DisplayName("FR-02: the UUID format accepts canonical UUIDs")
    void uuidFormatAcceptsCanonicalUuids(String value) {
        IdempotencyKeyParser parser = new IdempotencyKeyParser(KeyParsing.STRICT, 255, KeyFormat.UUID);
        assertThat(parser.parse(value)).isInstanceOf(KeyParseResult.Valid.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"\"4a1c7d9e5f3b4e8a9c217b0d3e6f8a12\"", "\"{4a1c7d9e-5f3b-4e8a-9c21-7b0d3e6f8a12}\"", "\"abc\""})
    @DisplayName("FR-02: the UUID format rejects other keys")
    void uuidFormatRejectsOtherKeys(String value) {
        IdempotencyKeyParser parser = new IdempotencyKeyParser(KeyParsing.STRICT, 255, KeyFormat.UUID);
        assertThat(parser.parse(value))
                .isEqualTo(new KeyParseResult.Invalid("The key must be a UUID in canonical form."));
    }

    @Test
    void rejectsNonPositiveMaxLength() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdempotencyKeyParser(KeyParsing.STRICT, 0, KeyFormat.ANY));
    }

    @Test
    void serializeEscapesQuotesAndBackslashes() {
        assertThat(IdempotencyKeyParser.serialize("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeyParser.serialize(""));
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeyParser.serialize("é"));
        assertThatIllegalArgumentException().isThrownBy(() -> IdempotencyKeyParser.serialize("\n"));
    }
}
