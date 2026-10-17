// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

import java.util.regex.Pattern;

/**
 * Parses {@code Idempotency-Key} field values.
 *
 * <p>The header is an RFC 9651 Item whose value is a String. Parameters on the item are parsed and ignored.
 */
public final class IdempotencyKeyParser {

    /** Default maximum key length after unescaping. */
    public static final int DEFAULT_MAX_LENGTH = 255;

    private static final Pattern LENIENT_KEY = Pattern.compile("[A-Za-z0-9._~+/=:-]+");
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final KeyParsing parsing;
    private final int maxLength;
    private final KeyFormat format;

    /** @throws IllegalArgumentException if {@code maxLength} is not positive */
    public IdempotencyKeyParser(KeyParsing parsing, int maxLength, KeyFormat format) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be positive: " + maxLength);
        }
        this.parsing = parsing;
        this.maxLength = maxLength;
        this.format = format;
    }

    /** Returns a strict parser with the default length limit and any format. */
    public static IdempotencyKeyParser defaults() {
        return new IdempotencyKeyParser(KeyParsing.STRICT, DEFAULT_MAX_LENGTH, KeyFormat.ANY);
    }

    /** Parses one field value. Repeated header lines, joined with a comma, are malformed. */
    public KeyParseResult parse(String fieldValue) {
        String value = trimWhitespace(fieldValue);
        String key;
        if (parsing == KeyParsing.LENIENT && LENIENT_KEY.matcher(value).matches()) {
            key = value;
        } else {
            String parsed = StructuredItemParser.parseStringItem(value);
            if (parsed == null) {
                return new KeyParseResult.Invalid(
                        parsing == KeyParsing.STRICT
                                ? "The key must be a quoted string, such as \"8e03978e-40d5-43e8-bc93-6894a57f9324\"."
                                : "The key must be a quoted string or use only A-Z a-z 0-9 - _ . ~ + / = :.");
            }
            key = parsed;
        }
        if (key.isEmpty()) {
            return new KeyParseResult.Invalid("The key must not be empty.");
        }
        if (key.length() > maxLength) {
            return new KeyParseResult.Invalid("The key must be at most " + maxLength + " characters long.");
        }
        if (format == KeyFormat.UUID && !CANONICAL_UUID.matcher(key).matches()) {
            return new KeyParseResult.Invalid("The key must be a UUID in canonical form.");
        }
        return new KeyParseResult.Valid(key);
    }

    /**
     * Serializes a key as an RFC 9651 String, escaping {@code "} and {@code \}.
     *
     * @throws IllegalArgumentException if the key is empty or has characters outside 0x20–0x7E
     */
    public static String serialize(String key) {
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key must not be empty");
        }
        StringBuilder out = new StringBuilder(key.length() + 2).append('"');
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new IllegalArgumentException("key has a character outside 0x20-0x7E at index " + i);
            }
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.append('"').toString();
    }

    // Field values carry no leading or trailing whitespace (RFC 9110 section 5.5).
    private static String trimWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }
}
