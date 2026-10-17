// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal.json;

/** Writes JSON string literals. */
public final class JsonStrings {

    private JsonStrings() {}

    /** Appends {@code value} as a JSON string, escaped as RFC 8785 section 3.2.2.2 requires. */
    public static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u00")
                                .append(Character.forDigit(c >> 4, 16))
                                .append(Character.forDigit(c & 0xF, 16));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
