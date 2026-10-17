// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DurationsTest {

    static Stream<Arguments> validDurations() {
        return Stream.of(
                Arguments.of("500ms", Duration.ofMillis(500)),
                Arguments.of("90s", Duration.ofSeconds(90)),
                Arguments.of("30m", Duration.ofMinutes(30)),
                Arguments.of("24h", Duration.ofHours(24)),
                Arguments.of("7d", Duration.ofDays(7)),
                Arguments.of(" 7d ", Duration.ofDays(7)),
                Arguments.of("PT24H", Duration.ofHours(24)),
                Arguments.of("P1DT0.5S", Duration.ofDays(1).plusMillis(500)),
                Arguments.of("pt1m", Duration.ofMinutes(1)));
    }

    @ParameterizedTest
    @MethodSource("validDurations")
    void parsesSimpleAndIsoDurations(String value, Duration expected) {
        assertThat(Durations.parse(value)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "24", "h", "24H", "1w", "-1s", "1.5h", "24 h", "PT", "P1X", "1000000000000000000d"})
    void rejectsMalformedValuesNamingThem(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Durations.parse(value))
                .withMessageStartingWith("Invalid duration '" + value + "'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "PT0S", "PT-1S"})
    void rejectsZeroAndNegativeDurations(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Durations.parse(value))
                .withMessage("Invalid duration '" + value + "': it must be positive");
    }

    static Stream<Arguments> formattedDurations() {
        return Stream.of(
                Arguments.of(Duration.ofDays(7), "7d"),
                Arguments.of(Duration.ofHours(25), "25h"),
                Arguments.of(Duration.ofMinutes(90), "90m"),
                Arguments.of(Duration.ofSeconds(61), "61s"),
                Arguments.of(Duration.ofMillis(1500), "1500ms"),
                Arguments.of(Duration.ofNanos(1), "PT0.000000001S"));
    }

    @ParameterizedTest
    @MethodSource("formattedDurations")
    void formatsInTheShortestExactForm(Duration duration, String expected) {
        assertThat(Durations.format(duration)).isEqualTo(expected);
    }

    @Property
    @Label("formatting then parsing a duration returns it unchanged")
    void formatThenParseRoundTrips(@ForAll @LongRange(min = 1, max = 3_153_600_000_000_000_000L) long nanos) {
        Duration duration = Duration.ofNanos(nanos);
        assertThat(Durations.parse(Durations.format(duration))).isEqualTo(duration);
    }
}
