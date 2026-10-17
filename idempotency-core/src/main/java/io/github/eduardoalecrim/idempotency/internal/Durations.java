// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses and formats the durations used by {@code @Idempotent} and the configuration. */
public final class Durations {

    private static final Pattern SIMPLE = Pattern.compile("(\\d{1,18})(ms|s|m|h|d)");

    private Durations() {}

    /**
     * Parses {@code 500ms}, {@code 90s}, {@code 30m}, {@code 24h}, {@code 7d} or ISO-8601 such as {@code PT24H}.
     *
     * @throws IllegalArgumentException naming the value, if it is malformed or not positive
     */
    public static Duration parse(String value) {
        Duration duration;
        try {
            duration = parseUnchecked(value.trim());
        } catch (ArithmeticException | DateTimeParseException e) {
            throw invalid(value);
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Invalid duration '" + value + "': it must be positive");
        }
        return duration;
    }

    /** Formats a duration in the shortest simple form that is exact, or ISO-8601 when there is none. */
    public static String format(Duration duration) {
        if (duration.toNanosPart() % 1_000_000 != 0) {
            return duration.toString();
        }
        long millis = duration.toMillis();
        if (millis % 86_400_000 == 0) {
            return millis / 86_400_000 + "d";
        }
        if (millis % 3_600_000 == 0) {
            return millis / 3_600_000 + "h";
        }
        if (millis % 60_000 == 0) {
            return millis / 60_000 + "m";
        }
        if (millis % 1_000 == 0) {
            return millis / 1_000 + "s";
        }
        return millis + "ms";
    }

    private static Duration parseUnchecked(String value) {
        Matcher simple = SIMPLE.matcher(value);
        if (!simple.matches()) {
            if (!value.startsWith("P") && !value.startsWith("p")) {
                throw invalid(value);
            }
            return Duration.parse(value);
        }
        long amount = Long.parseLong(simple.group(1));
        return switch (simple.group(2)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofDays(amount);
        };
    }

    private static IllegalArgumentException invalid(String value) {
        return new IllegalArgumentException("Invalid duration '" + value
                + "': use a number with ms, s, m, h or d, such as 24h, or ISO-8601, such as PT24H");
    }
}
