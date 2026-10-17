// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.internal.json;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Canonicalizes JSON documents with RFC 8785 (JCS).
 *
 * <p>Input must be UTF-8 I-JSON (RFC 7493): no duplicate member names, no lone surrogates, numbers within double
 * range. Members are sorted by UTF-16 code units, which is {@link String#compareTo(String)}.
 */
public final class JsonCanonicalizer {

    // Deeper documents are reported as invalid instead of overflowing the stack.
    private static final int MAX_DEPTH = 256;

    private JsonCanonicalizer() {}

    /** Returns the canonical UTF-8 bytes, or empty when {@code utf8} is not a valid I-JSON document. */
    public static Optional<byte[]> canonicalize(byte[] utf8) {
        String text;
        try {
            text = decode(utf8);
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
        Object value;
        try {
            Parser parser = new Parser(text);
            value = parser.parseDocument();
        } catch (InvalidJsonException e) {
            return Optional.empty();
        }
        StringBuilder out = new StringBuilder(text.length());
        write(value, out);
        return Optional.of(out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(byte[] utf8) throws CharacterCodingException {
        CharBuffer chars = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(utf8));
        return chars.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value instanceof Map<?, ?> object) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> member : object.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                JsonStrings.quote((String) member.getKey(), out);
                out.append(':');
                write(member.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof List<?> array) {
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(array.get(i), out);
            }
            out.append(']');
        } else if (value instanceof String string) {
            JsonStrings.quote(string, out);
        } else if (value instanceof Double number) {
            out.append(JcsNumbers.serialize(number));
        } else {
            out.append(((Literal) value).text);
        }
    }

    private enum Literal {
        NULL("null"),
        TRUE("true"),
        FALSE("false");

        private final String text;

        Literal(String text) {
            this.text = text;
        }
    }

    private static final class InvalidJsonException extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidJsonException() {
            super(null, null, false, false);
        }
    }

    // Recursive descent parser for RFC 8259.
    private static final class Parser {

        private final String text;
        private int pos;
        private int depth;

        Parser(String text) {
            this.text = text;
        }

        Object parseDocument() throws InvalidJsonException {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw new InvalidJsonException();
            }
            return value;
        }

        private Object parseValue() throws InvalidJsonException {
            if (pos >= text.length()) {
                throw new InvalidJsonException();
            }
            return switch (text.charAt(pos)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral(Literal.TRUE);
                case 'f' -> parseLiteral(Literal.FALSE);
                case 'n' -> parseLiteral(Literal.NULL);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() throws InvalidJsonException {
            enter();
            pos++;
            Map<String, Object> members = new TreeMap<>();
            skipWhitespace();
            if (consume('}')) {
                depth--;
                return members;
            }
            do {
                skipWhitespace();
                if (!at('"')) {
                    throw new InvalidJsonException();
                }
                String name = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                if (members.put(name, parseValue()) != null) {
                    throw new InvalidJsonException();
                }
                skipWhitespace();
            } while (consume(','));
            expect('}');
            depth--;
            return members;
        }

        private List<Object> parseArray() throws InvalidJsonException {
            enter();
            pos++;
            List<Object> elements = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                depth--;
                return elements;
            }
            do {
                skipWhitespace();
                elements.add(parseValue());
                skipWhitespace();
            } while (consume(','));
            expect(']');
            depth--;
            return elements;
        }

        private String parseString() throws InvalidJsonException {
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    String value = out.toString();
                    checkSurrogates(value);
                    return value;
                }
                if (c < 0x20) {
                    throw new InvalidJsonException();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw new InvalidJsonException();
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(parseHex4());
                    default -> throw new InvalidJsonException();
                }
            }
            throw new InvalidJsonException();
        }

        private char parseHex4() throws InvalidJsonException {
            if (pos + 4 > text.length()) {
                throw new InvalidJsonException();
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = text.charAt(pos++);
                int digit;
                if (c >= '0' && c <= '9') {
                    digit = c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    digit = c - 'a' + 10;
                } else if (c >= 'A' && c <= 'F') {
                    digit = c - 'A' + 10;
                } else {
                    throw new InvalidJsonException();
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        // I-JSON forbids lone surrogates, which escapes can produce.
        private static void checkSurrogates(String value) throws InvalidJsonException {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                        throw new InvalidJsonException();
                    }
                    i++;
                } else if (Character.isLowSurrogate(c)) {
                    throw new InvalidJsonException();
                }
            }
        }

        private Object parseNumber() throws InvalidJsonException {
            int start = pos;
            consume('-');
            // A leading zero stands alone.
            if (!consume('0') && !skipDigits()) {
                throw new InvalidJsonException();
            }
            if (consume('.') && !skipDigits()) {
                throw new InvalidJsonException();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                if (!skipDigits()) {
                    throw new InvalidJsonException();
                }
            }
            double value = Double.parseDouble(text.substring(start, pos));
            if (Double.isInfinite(value)) {
                throw new InvalidJsonException();
            }
            return value;
        }

        private boolean skipDigits() {
            int start = pos;
            while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
                pos++;
            }
            return pos > start;
        }

        private Literal parseLiteral(Literal literal) throws InvalidJsonException {
            if (!text.startsWith(literal.text, pos)) {
                throw new InvalidJsonException();
            }
            pos += literal.text.length();
            return literal;
        }

        private void enter() throws InvalidJsonException {
            if (++depth > MAX_DEPTH) {
                throw new InvalidJsonException();
            }
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                    return;
                }
                pos++;
            }
        }

        private boolean at(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }

        private boolean consume(char c) {
            if (at(c)) {
                pos++;
                return true;
            }
            return false;
        }

        private void expect(char c) throws InvalidJsonException {
            if (!consume(c)) {
                throw new InvalidJsonException();
            }
        }
    }
}
