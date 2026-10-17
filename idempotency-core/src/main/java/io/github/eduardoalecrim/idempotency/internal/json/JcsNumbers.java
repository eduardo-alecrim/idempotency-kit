// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal.json;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Serializes doubles as ECMAScript {@code Number.prototype.toString} does, which RFC 8785 section 3.2.2.3 requires.
 *
 * <p>{@link Double#toString(double)} is not used: before JDK 19 it does not always produce the shortest digits.
 */
final class JcsNumbers {

    private static final double MAX_SAFE_INTEGER = 9007199254740992.0;

    private JcsNumbers() {}

    /** @throws IllegalArgumentException for NaN and infinities, which JSON cannot represent */
    static String serialize(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("not a JSON number: " + value);
        }
        if (value == 0) {
            return "0";
        }
        double abs = Math.abs(value);
        String sign = value < 0 ? "-" : "";
        // Every integer up to 2^53 is exact, so its digits are already the shortest.
        if (abs <= MAX_SAFE_INTEGER && abs == Math.rint(abs)) {
            return sign + (long) abs;
        }
        return sign + format(shortest(abs));
    }

    // The shortest decimal that parses back to the same double; the closer one, then the even one, on ties.
    private static BigDecimal shortest(double abs) {
        BigDecimal exact = new BigDecimal(abs);
        for (int precision = 1; precision < 17; precision++) {
            BigDecimal down = exact.round(new MathContext(precision, RoundingMode.FLOOR));
            BigDecimal up = exact.round(new MathContext(precision, RoundingMode.CEILING));
            boolean downMatches = Double.parseDouble(down.toString()) == abs;
            boolean upMatches = Double.parseDouble(up.toString()) == abs;
            if (downMatches && upMatches) {
                int closer = exact.subtract(down).compareTo(up.subtract(exact));
                if (closer != 0) {
                    return closer < 0 ? down : up;
                }
                return down.unscaledValue().testBit(0) ? up : down;
            }
            if (downMatches) {
                return down;
            }
            if (upMatches) {
                return up;
            }
        }
        // 17 significant digits always round-trip.
        return exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
    }

    // ECMAScript Number::toString, steps 6 to 10, for a positive value.
    private static String format(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        String digits = stripped.unscaledValue().toString();
        int k = digits.length();
        int n = k - stripped.scale();
        if (k <= n && n <= 21) {
            return digits + "0".repeat(n - k);
        }
        if (0 < n && n <= 21) {
            return digits.substring(0, n) + "." + digits.substring(n);
        }
        if (-6 < n && n <= 0) {
            return "0." + "0".repeat(-n) + digits;
        }
        String exponent = (n - 1 < 0 ? "-" : "+") + Math.abs(n - 1);
        if (k == 1) {
            return digits + "e" + exponent;
        }
        return digits.charAt(0) + "." + digits.substring(1) + "e" + exponent;
    }
}
