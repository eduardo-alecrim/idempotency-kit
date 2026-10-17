// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.key;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Parses an RFC 9651 Item whose bare item is a String. Parameters are validated and dropped.
 *
 * <p>Follows the parsing algorithms of RFC 9651 section 4.2.
 */
final class StructuredItemParser {

    private final String input;
    private int pos;

    private StructuredItemParser(String input) {
        this.input = input;
    }

    /** Returns the unescaped string, or {@code null} when {@code input} is not a String Item. */
    static @Nullable String parseStringItem(String input) {
        StructuredItemParser parser = new StructuredItemParser(input);
        parser.skipSpaces();
        if (!parser.at('"')) {
            return null;
        }
        String value = parser.parseString();
        if (value == null || !parser.parseParameters()) {
            return null;
        }
        parser.skipSpaces();
        return parser.pos == input.length() ? value : null;
    }

    private boolean parseParameters() {
        while (at(';')) {
            pos++;
            skipSpaces();
            if (!parseKey()) {
                return false;
            }
            if (at('=')) {
                pos++;
                if (!parseBareItem()) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean parseKey() {
        if (pos >= input.length()) {
            return false;
        }
        char first = input.charAt(pos);
        if (!isLcAlpha(first) && first != '*') {
            return false;
        }
        pos++;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (!isLcAlpha(c) && !isDigit(c) && c != '_' && c != '-' && c != '.' && c != '*') {
                break;
            }
            pos++;
        }
        return true;
    }

    private boolean parseBareItem() {
        if (pos >= input.length()) {
            return false;
        }
        char c = input.charAt(pos);
        if (c == '-' || isDigit(c)) {
            return parseNumber(true);
        }
        if (c == '"') {
            return parseString() != null;
        }
        if (c == '*' || isAlpha(c)) {
            return parseToken();
        }
        if (c == ':') {
            return parseByteSequence();
        }
        if (c == '?') {
            return parseBoolean();
        }
        if (c == '@') {
            pos++;
            return parseNumber(false);
        }
        if (c == '%') {
            return parseDisplayString();
        }
        return false;
    }

    private boolean parseNumber(boolean decimalAllowed) {
        if (at('-')) {
            pos++;
        }
        if (pos >= input.length() || !isDigit(input.charAt(pos))) {
            return false;
        }
        int start = pos;
        int dot = -1;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (isDigit(c)) {
                pos++;
            } else if (c == '.' && decimalAllowed && dot < 0) {
                if (pos - start > 12) {
                    return false;
                }
                dot = pos++;
            } else {
                break;
            }
            if (dot < 0 && pos - start > 15) {
                return false;
            }
            if (dot >= 0 && pos - start > 16) {
                return false;
            }
        }
        int fraction = pos - dot - 1;
        return dot < 0 || (fraction >= 1 && fraction <= 3);
    }

    private @Nullable String parseString() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == '\\') {
                if (pos >= input.length()) {
                    return null;
                }
                char next = input.charAt(pos++);
                if (next != '"' && next != '\\') {
                    return null;
                }
                out.append(next);
            } else if (c == '"') {
                return out.toString();
            } else if (c < 0x20 || c > 0x7E) {
                return null;
            } else {
                out.append(c);
            }
        }
        return null;
    }

    private boolean parseToken() {
        pos++;
        while (pos < input.length()) {
            char c = input.charAt(pos);
            if (!isTchar(c) && c != ':' && c != '/') {
                break;
            }
            pos++;
        }
        return true;
    }

    private boolean parseByteSequence() {
        pos++;
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c == ':') {
                return true;
            }
            if (!isAlpha(c) && !isDigit(c) && c != '+' && c != '/' && c != '=') {
                return false;
            }
        }
        return false;
    }

    private boolean parseBoolean() {
        pos++;
        if (at('0') || at('1')) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean parseDisplayString() {
        pos++;
        if (!at('"')) {
            return false;
        }
        pos++;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (pos < input.length()) {
            char c = input.charAt(pos++);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
            if (c == '%') {
                if (pos + 2 > input.length()
                        || !isLowerHexDigit(input.charAt(pos))
                        || !isLowerHexDigit(input.charAt(pos + 1))) {
                    return false;
                }
                bytes.write(Integer.parseInt(input.substring(pos, pos + 2), 16));
                pos += 2;
            } else if (c == '"') {
                return isUtf8(bytes.toByteArray());
            } else {
                bytes.write(c);
            }
        }
        return false;
    }

    private void skipSpaces() {
        while (at(' ')) {
            pos++;
        }
    }

    private boolean at(char c) {
        return pos < input.length() && input.charAt(pos) == c;
    }

    private static boolean isUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private static boolean isLowerHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f');
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isLcAlpha(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean isAlpha(char c) {
        return isLcAlpha(c) || (c >= 'A' && c <= 'Z');
    }

    // RFC 9110 tchar
    private static boolean isTchar(char c) {
        return isAlpha(c) || isDigit(c) || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
    }
}
