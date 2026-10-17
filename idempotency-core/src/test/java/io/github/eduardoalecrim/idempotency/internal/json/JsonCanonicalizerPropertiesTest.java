// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal.json;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class JsonCanonicalizerPropertiesTest {

    @Property
    @Label("canon(canon(x)) == canon(x)")
    void canonicalizationIsIdempotent(@ForAll("documents") String document) {
        byte[] once = JsonCanonicalizer.canonicalize(document.getBytes(UTF_8)).orElseThrow();

        assertThat(JsonCanonicalizer.canonicalize(once)).hasValue(once);
    }

    @Property
    @Label("canonical numbers parse back to the same double and are stable")
    void numbersRoundTrip(@ForAll("finiteDoubles") double value) {
        byte[] once = JsonCanonicalizer.canonicalize(Double.toString(value).getBytes(UTF_8))
                .orElseThrow();

        assertThat(Double.parseDouble(new String(once, UTF_8))).isEqualTo(value == 0 ? 0.0 : value);
        assertThat(JsonCanonicalizer.canonicalize(once)).hasValue(once);
    }

    @Provide
    Arbitrary<Double> finiteDoubles() {
        return Arbitraries.longs().map(Double::longBitsToDouble).filter(Double::isFinite);
    }

    @Provide
    Arbitrary<String> documents() {
        return value(3);
    }

    private static Arbitrary<String> value(int depth) {
        Arbitrary<String> leaf = Arbitraries.oneOf(
                finiteDoubleTexts(),
                Arbitraries.longs().map(String::valueOf),
                strings(),
                Arbitraries.of("true", "false", "null"));
        if (depth == 0) {
            return leaf;
        }
        Arbitrary<String> array =
                value(depth - 1).list().ofMaxSize(4).map(elements -> "[ " + String.join(" ,\n", elements) + " ]");
        Arbitrary<String> object =
                Arbitraries.maps(strings(), value(depth - 1)).ofMaxSize(4).map(JsonCanonicalizerPropertiesTest::object);
        return Arbitraries.oneOf(leaf, array, object);
    }

    private static Arbitrary<String> finiteDoubleTexts() {
        return Arbitraries.longs()
                .map(Double::longBitsToDouble)
                .filter(Double::isFinite)
                .map(String::valueOf);
    }

    // Strings with every BMP character outside the surrogate range, escaped as JSON allows.
    private static Arbitrary<String> strings() {
        return Arbitraries.strings()
                .withCharRange('\u0000', '\uD7FF')
                .withCharRange('\uE000', '\uFFFF')
                .ofMaxLength(8)
                .map(JsonCanonicalizerPropertiesTest::quote);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20 || c % 7 == 0) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String object(Map<String, String> members) {
        return members.entrySet().stream()
                .map(member -> member.getKey() + " : " + member.getValue())
                .collect(Collectors.joining(", ", "{", "}"));
    }
}
