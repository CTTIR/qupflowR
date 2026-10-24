/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reads JSON text under the decoding rules shared with the R package ({@code qp_json_decode()},
 * decision D022). It is the input stage of {@link CanonicalJson} and of the schema validator.
 *
 * <p>Accepted text is RFC 8259 JSON in UTF-8. Rejected, with the same code and reason as in R:
 *
 * <ul>
 *   <li>{@code JSON_INVALID}: NUL bytes ({@code nul_byte}), invalid UTF-8 ({@code invalid_utf8}), a
 *       leading byte order mark ({@code byte_order_mark}), unpaired surrogate escapes ({@code
 *       unpaired_surrogate}), nesting deeper than 128 levels ({@code nesting_too_deep}), duplicate
 *       member names ({@code duplicate_key}) and every grammar violation ({@code syntax});
 *   <li>{@code JSON_UNREPRESENTABLE}: an escaped U+0000 ({@code nul_character}), an empty member
 *       name ({@code empty_member_name}) and numbers outside the double range ({@code
 *       number_out_of_range}).
 * </ul>
 *
 * <p>When a text has several defects, the reported one follows the order of the R checks: text
 * encoding, escape scan, bracket depth scan (texts longer than 256 UTF-8 bytes), grammar (an
 * unterminated string, then a solidus, U+000B or U+000C outside strings), parsing, and then a
 * depth-first walk that checks depth, empty and duplicate member names and number range per node.
 * The value at depth 128 (the root has depth 0) is already too deep. The scans before parsing find
 * string literals with {@link JsonTextScan} and take time linear in the length of the text.
 *
 * <p>Numbers become {@link BigDecimal} primitives holding the exact decimal value of the token.
 */
public final class CanonicalJsonReader {

    /** Maximum number of nesting levels; a value at this depth is rejected. */
    public static final int MAX_DEPTH = 128;

    /** Texts longer than this many UTF-8 bytes get the bracket depth scan before parsing. */
    static final int DEPTH_SCAN_MIN_BYTES = 2 * MAX_DEPTH;

    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /** Recursion guard; unreachable for texts that pass the bracket depth scan. */
    private static final int PARSER_DEPTH_GUARD = 4 * DEPTH_SCAN_MIN_BYTES;

    private CanonicalJsonReader() {}

    /**
     * Reads JSON from UTF-8 bytes.
     *
     * @param utf8 JSON text as UTF-8 bytes
     * @return the JSON value; numbers are {@link BigDecimal} primitives
     * @throws CanonicalJsonException if the text is rejected
     */
    public static JsonElement read(byte[] utf8) {
        return read(decodeUtf8(utf8));
    }

    /**
     * Reads JSON text.
     *
     * @param text JSON text
     * @return the JSON value; numbers are {@link BigDecimal} primitives
     * @throws CanonicalJsonException if the text is rejected
     */
    public static JsonElement read(String text) {
        checkText(text);
        JsonTextScan scan = JsonTextScan.of(text);
        if (text.contains("\\u")) {
            scan.checkEscapes();
        }
        if (utf8Length(text) > DEPTH_SCAN_MIN_BYTES && scan.nestingExceeds(MAX_DEPTH)) {
            throw CanonicalJsonException.invalid(CanonicalJsonException.NESTING_TOO_DEEP, null);
        }
        if (scan.unterminated() || scan.hasReservedCharacterOutsideStrings()) {
            throw CanonicalJsonException.invalid(CanonicalJsonException.SYNTAX, null);
        }
        Node root = new Parser(text).document();
        checkTree(root, "", 0);
        return root.toElement();
    }

    /**
     * Decodes UTF-8 bytes into text, rejecting NUL bytes and malformed UTF-8.
     *
     * @param utf8 bytes
     * @return decoded text
     * @throws CanonicalJsonException with reason {@code nul_byte} or {@code invalid_utf8}
     */
    static String decodeUtf8(byte[] utf8) {
        Objects.requireNonNull(utf8, "utf8");
        for (byte b : utf8) {
            if (b == 0) {
                throw CanonicalJsonException.invalid(CanonicalJsonException.NUL_BYTE, null);
            }
        }
        try {
            CharBuffer chars =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(utf8));
            return chars.toString();
        } catch (CharacterCodingException e) {
            throw CanonicalJsonException.invalid(CanonicalJsonException.INVALID_UTF8, null);
        }
    }

    /**
     * Checks the text itself: no NUL character, well-formed UTF-16 and no byte order mark.
     *
     * @param text text to check
     */
    static void checkText(String text) {
        Objects.requireNonNull(text, "text");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 0) {
                throw CanonicalJsonException.invalid(CanonicalJsonException.NUL_BYTE, null);
            }
            if (Character.isHighSurrogate(c)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                throw CanonicalJsonException.invalid(CanonicalJsonException.INVALID_UTF8, null);
            }
        }
        if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
            throw CanonicalJsonException.invalid(CanonicalJsonException.BYTE_ORDER_MARK, null);
        }
    }

    static int utf8Length(String text) {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c)) {
                bytes += 4;
                i++;
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private static void checkTree(Node node, String pointer, int depth) {
        if (depth >= MAX_DEPTH) {
            throw CanonicalJsonException.invalid(CanonicalJsonException.NESTING_TOO_DEEP, null);
        }
        switch (node) {
            case ObjectNode object -> {
                if (object.names.contains("")) {
                    throw CanonicalJsonException.unrepresentable(
                            CanonicalJsonException.EMPTY_MEMBER_NAME, pointer + "/");
                }
                Set<String> seen = new HashSet<>();
                for (String name : object.names) {
                    if (!seen.add(name)) {
                        throw CanonicalJsonException.invalid(
                                CanonicalJsonException.DUPLICATE_KEY,
                                pointer + "/" + ProtocolJson.escapePointerToken(name));
                    }
                }
                for (int i = 0; i < object.names.size(); i++) {
                    String child =
                            pointer + "/" + ProtocolJson.escapePointerToken(object.names.get(i));
                    checkTree(object.values.get(i), child, depth + 1);
                }
            }
            case ArrayNode array -> {
                for (int i = 0; i < array.items.size(); i++) {
                    checkTree(array.items.get(i), pointer + "/" + i, depth + 1);
                }
            }
            case NumberNode number -> {
                if (Double.isInfinite(Double.parseDouble(number.token))) {
                    throw CanonicalJsonException.unrepresentable(
                            CanonicalJsonException.NUMBER_OUT_OF_RANGE, pointer);
                }
            }
            default -> {
                // strings and literals have no further rules
            }
        }
    }

    /** Parsed value before the tree checks; objects keep duplicate names. */
    private sealed interface Node permits ObjectNode, ArrayNode, StringNode, NumberNode, Literal {
        JsonElement toElement();
    }

    private record ObjectNode(List<String> names, List<Node> values) implements Node {
        @Override
        public JsonElement toElement() {
            JsonObject object = new JsonObject();
            for (int i = 0; i < names.size(); i++) {
                object.add(names.get(i), values.get(i).toElement());
            }
            return object;
        }
    }

    private record ArrayNode(List<Node> items) implements Node {
        @Override
        public JsonElement toElement() {
            JsonArray array = new JsonArray(items.size());
            for (Node item : items) {
                array.add(item.toElement());
            }
            return array;
        }
    }

    private record StringNode(String value) implements Node {
        @Override
        public JsonElement toElement() {
            return new JsonPrimitive(value);
        }
    }

    private record NumberNode(String token) implements Node {
        @Override
        public JsonElement toElement() {
            try {
                return new JsonPrimitive(new BigDecimal(token));
            } catch (NumberFormatException e) {
                // Exponent beyond the int range; the double value (0 after underflow) is kept.
                return new JsonPrimitive(
                        new BigDecimal(Double.toString(Double.parseDouble(token))));
            }
        }
    }

    private enum Literal implements Node {
        TRUE,
        FALSE,
        NULL;

        @Override
        public JsonElement toElement() {
            return switch (this) {
                case TRUE -> new JsonPrimitive(true);
                case FALSE -> new JsonPrimitive(false);
                case NULL -> JsonNull.INSTANCE;
            };
        }
    }

    /** Recursive descent parser for the RFC 8259 grammar; every violation is {@code syntax}. */
    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        Node document() {
            skipWhitespace();
            Node value = value(0);
            skipWhitespace();
            if (pos != text.length()) {
                throw syntax();
            }
            return value;
        }

        private Node value(int depth) {
            if (depth > PARSER_DEPTH_GUARD || pos >= text.length()) {
                throw syntax();
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> new StringNode(string());
                case 't' -> literal("true", Literal.TRUE);
                case 'f' -> literal("false", Literal.FALSE);
                case 'n' -> literal("null", Literal.NULL);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw syntax();
                }
            };
        }

        private Node object(int depth) {
            pos++;
            List<String> names = new ArrayList<>();
            List<Node> values = new ArrayList<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return new ObjectNode(names, values);
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw syntax();
                }
                names.add(string());
                skipWhitespace();
                expect(':');
                skipWhitespace();
                values.add(value(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == '}') {
                    return new ObjectNode(names, values);
                }
                if (c != ',') {
                    throw syntax();
                }
            }
        }

        private Node array(int depth) {
            pos++;
            List<Node> items = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return new ArrayNode(items);
            }
            while (true) {
                skipWhitespace();
                items.add(value(depth + 1));
                skipWhitespace();
                char c = peek();
                pos++;
                if (c == ']') {
                    return new ArrayNode(items);
                }
                if (c != ',') {
                    throw syntax();
                }
            }
        }

        private String string() {
            pos++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= text.length()) {
                    throw syntax();
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw syntax();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= text.length()) {
                    throw syntax();
                }
                char e = text.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> appendEscapedUnit(out);
                    default -> throw syntax();
                }
            }
        }

        private void appendEscapedUnit(StringBuilder out) {
            char unit = hex4();
            if (unit == 0) {
                throw CanonicalJsonException.unrepresentable(
                        CanonicalJsonException.NUL_CHARACTER, "");
            }
            if (Character.isHighSurrogate(unit)) {
                if (text.startsWith("\\u", pos)) {
                    pos += 2;
                    char low = hex4();
                    if (Character.isLowSurrogate(low)) {
                        out.append(unit).append(low);
                        return;
                    }
                }
                throw CanonicalJsonException.invalid(
                        CanonicalJsonException.UNPAIRED_SURROGATE, null);
            }
            if (Character.isLowSurrogate(unit)) {
                throw CanonicalJsonException.invalid(
                        CanonicalJsonException.UNPAIRED_SURROGATE, null);
            }
            out.append(unit);
        }

        private char hex4() {
            if (pos + 4 > text.length()) {
                throw syntax();
            }
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = text.charAt(pos + i);
                int digit = c > 'f' ? -1 : Character.digit(c, 16);
                if (digit < 0) {
                    throw syntax();
                }
                value = value * 16 + digit;
            }
            pos += 4;
            return (char) value;
        }

        private Node number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            if (peek() == '0') {
                pos++;
            } else if (isDigit(peek())) {
                skipDigits();
            } else {
                throw syntax();
            }
            if (peek() == '.') {
                pos++;
                if (!isDigit(peek())) {
                    throw syntax();
                }
                skipDigits();
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++;
                if (peek() == '+' || peek() == '-') {
                    pos++;
                }
                if (!isDigit(peek())) {
                    throw syntax();
                }
                skipDigits();
            }
            return new NumberNode(text.substring(start, pos));
        }

        private Node literal(String word, Literal literal) {
            if (!text.startsWith(word, pos)) {
                throw syntax();
            }
            pos += word.length();
            return literal;
        }

        private void skipDigits() {
            while (isDigit(peek())) {
                pos++;
            }
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        private void expect(char c) {
            if (peek() != c) {
                throw syntax();
            }
            pos++;
        }

        /** The next character, or NUL at the end (NUL never occurs in checked text). */
        private char peek() {
            return pos < text.length() ? text.charAt(pos) : 0;
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

        private static CanonicalJsonException syntax() {
            return CanonicalJsonException.invalid(CanonicalJsonException.SYNTAX, null);
        }
    }
}
