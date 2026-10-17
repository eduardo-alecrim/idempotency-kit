// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("RFC 8785 test vectors")
class JcsVectorsTest {

    @ParameterizedTest
    @ValueSource(strings = {"arrays", "french", "structures", "unicode", "values", "weird"})
    void canonicalizesReferenceInputs(String name) {
        byte[] input = resource("jcs/input/" + name + ".json");
        byte[] expected = resource("jcs/output/" + name + ".json");

        assertThat(JsonCanonicalizer.canonicalize(input)).hasValue(expected);
    }

    // RFC 8785 Appendix B
    @ParameterizedTest
    @CsvSource({
        "0000000000000000, 0",
        "8000000000000000, 0",
        "0000000000000001, 5e-324",
        "8000000000000001, -5e-324",
        "7fefffffffffffff, 1.7976931348623157e+308",
        "ffefffffffffffff, -1.7976931348623157e+308",
        "4340000000000000, 9007199254740992",
        "c340000000000000, -9007199254740992",
        "4430000000000000, 295147905179352830000",
        "44b52d02c7e14af5, 9.999999999999997e+22",
        "44b52d02c7e14af6, 1e+23",
        "44b52d02c7e14af7, 1.0000000000000001e+23",
        "444b1ae4d6e2ef4e, 999999999999999700000",
        "444b1ae4d6e2ef4f, 999999999999999900000",
        "444b1ae4d6e2ef50, 1e+21",
        "3eb0c6f7a0b5ed8c, 9.999999999999997e-7",
        "3eb0c6f7a0b5ed8d, 0.000001",
        "41b3de4355555553, 333333333.3333332",
        "41b3de4355555554, 333333333.33333325",
        "41b3de4355555555, 333333333.3333333",
        "41b3de4355555556, 333333333.3333334",
        "41b3de4355555557, 333333333.33333343",
        "becbf647612f3696, -0.0000033333333333333333",
        "43143ff3c1cb0959, 1424953923781206.2"
    })
    void serializesNumberSamples(String ieee, String expected) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(ieee, 16));

        assertThat(JcsNumbers.serialize(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"7fffffffffffffff", "7ff0000000000000", "fff0000000000000"})
    void rejectsNanAndInfinity(String ieee) {
        double value = Double.longBitsToDouble(Long.parseUnsignedLong(ieee, 16));

        assertThatIllegalArgumentException().isThrownBy(() -> JcsNumbers.serialize(value));
    }

    private static byte[] resource(String path) {
        try (InputStream in =
                Objects.requireNonNull(JcsVectorsTest.class.getClassLoader().getResourceAsStream(path))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
