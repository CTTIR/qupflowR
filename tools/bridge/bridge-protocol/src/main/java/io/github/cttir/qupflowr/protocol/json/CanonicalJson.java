/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical JSON, the digest input shared with the R package ({@code qp_json_canonical()}, decision
 * D022). Equal JSON values give byte-identical canonical text in both languages.
 *
 * <ol>
 *   <li>No insignificant white space and no trailing newline.
 *   <li>Object members are sorted by name, compared as sequences of Unicode code points (the order
 *       of the UTF-8 bytes; RFC 8785 compares UTF-16 code units instead). Names are unique and
 *       non-empty.
 *   <li>Strings are UTF-8. Quotation mark and reverse solidus are escaped with a reverse solidus;
 *       U+0008, U+0009, U+000A, U+000C and U+000D use the short escapes {@code b t n f r}; other
 *       characters below U+0020 use a six-character escape with four lower-case hex digits; every
 *       other character, including U+007F, U+2028 and non-ASCII text, is written literally.
 *   <li>Numbers are integers with a magnitude of at most 2^53 - 1, written as digits with an
 *       optional minus sign; negative zero is {@code 0}. Other numbers are rejected ({@code
 *       JSON_UNREPRESENTABLE}, reason {@code non_integer_number} or {@code integer_out_of_range}).
 *       In JSON text every number token with a fraction or an exponent is rejected, even {@code
 *       1.0}.
 *   <li>Arrays keep their order.
 * </ol>
 *
 * <p>Text input first passes {@link CanonicalJsonReader} after a scan of the number tokens, in the
 * same order of checks as the R package, so a rejected text reports the same code and reason.
 */
public final class CanonicalJson {

    /** Largest integer magnitude allowed in canonical JSON (2^53 - 1). */
    public static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;

    private static final BigDecimal MAX_SAFE = BigDecimal.valueOf(MAX_SAFE_INTEGER);

    /** Number-like tokens outside string literals (the R scan uses the same expression). */
    private static final Pattern NUMBER_TOKEN = Pattern.compile("-?[0-9][0-9.eE+-]*");

    private static final HexFormat HEX = HexFormat.of();

    private CanonicalJson() {}

    /**
     * Canonicalises JSON text given as UTF-8 bytes.
     *
     * @param utf8 JSON text as UTF-8 bytes
     * @return canonical UTF-8 bytes
     * @throws CanonicalJsonException if the text is rejected
     */
    public static byte[] canonicalize(byte[] utf8) {
        return canonicalize(CanonicalJsonReader.decodeUtf8(utf8)).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Canonicalises JSON text.
     *
     * @param text JSON text
     * @return canonical text
     * @throws CanonicalJsonException if the text is rejected
     */
    public static String canonicalize(String text) {
        CanonicalJsonReader.checkText(text);
        checkNumberTokens(text);
        return write(CanonicalJsonReader.read(text));
    }

    /**
     * Writes a JSON value as canonical JSON.
     *
     * <p>Numbers are taken by their exact value: {@code 1.0} and {@code 5E+9} are integers, {@code
     * 0.5} is not. A {@code null} reference is JSON {@code null}.
     *
     * @param value JSON value (for example from {@link CanonicalJsonReader} or {@link
     *     ProtocolJson})
     * @return canonical text
     * @throws CanonicalJsonException if the value cannot be written as canonical JSON (non-integer,
     *     out-of-range or non-finite number, empty member name, U+0000 or an unpaired surrogate in
     *     a string, nesting of 128 levels or more)
     */
    public static String write(JsonElement value) {
        StringBuilder out = new StringBuilder();
        writeValue(out, value, "", 0);
        return out.toString();
    }

    /**
     * Returns the SHA-256 digest of the canonical form of a JSON value.
     *
     * @param value JSON value
     * @return 64 lower-case hexadecimal digits
     * @throws CanonicalJsonException if the value cannot be written as canonical JSON
     */
    public static String sha256Hex(JsonElement value) {
        return sha256Hex(write(value).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the SHA-256 digest of bytes.
     *
     * @param bytes input bytes
     * @return 64 lower-case hexadecimal digits
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    /**
     * Scans number tokens outside string literals before parsing: any fraction or exponent is
     * {@code non_integer_number}; more than 16 digits or a value above 2^53 - 1 is {@code
     * integer_out_of_range}. An unterminated string counts as a string up to the end of the text.
     */
    static void checkNumberTokens(String text) {
        String stripped = JsonTextScan.of(text).maskedText();
        List<String> tokens = new ArrayList<>();
        Matcher m = NUMBER_TOKEN.matcher(stripped);
        while (m.find()) {
            tokens.add(m.group());
        }
        for (String token : tokens) {
            if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
                throw CanonicalJsonException.unrepresentable(
                        CanonicalJsonException.NON_INTEGER_NUMBER, "");
            }
        }
        for (String token : tokens) {
            String digits = token.startsWith("-") ? token.substring(1) : token;
            boolean tooLong = digits.length() > 16;
            boolean tooLarge =
                    !tooLong
                            && digits.chars().allMatch(c -> c >= '0' && c <= '9')
                            && Long.parseLong(digits) > MAX_SAFE_INTEGER;
            if (tooLong || tooLarge) {
                throw CanonicalJsonException.unrepresentable(
                        CanonicalJsonException.INTEGER_OUT_OF_RANGE, "");
            }
        }
    }

    private static void writeValue(
            StringBuilder out, JsonElement value, String pointer, int depth) {
        if (depth >= CanonicalJsonReader.MAX_DEPTH) {
            throw CanonicalJsonException.unrepresentable(
                    CanonicalJsonException.NESTING_TOO_DEEP, pointer);
        }
        if (value == null || value.isJsonNull()) {
            out.append("null");
        } else if (value instanceof JsonObject object) {
            writeObject(out, object, pointer, depth);
        } else if (value instanceof JsonArray array) {
            out.append('[');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                writeValue(out, array.get(i), pointer + "/" + i, depth + 1);
            }
            out.append(']');
        } else {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                out.append(primitive.getAsBoolean() ? "true" : "false");
            } else if (primitive.isNumber()) {
                out.append(integerDigits(primitive.getAsNumber(), pointer));
            } else {
                writeString(out, primitive.getAsString(), pointer);
            }
        }
    }

    private static void writeObject(
            StringBuilder out, JsonObject object, String pointer, int depth) {
        List<Member> members = new ArrayList<>(object.size());
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String name = entry.getKey();
            if (name.isEmpty()) {
                throw CanonicalJsonException.unrepresentable(
                        CanonicalJsonException.EMPTY_MEMBER_NAME, pointer);
            }
            if (!isWellFormed(name)) {
                throw CanonicalJsonException.unrepresentable(
                        CanonicalJsonException.INVALID_UTF8, pointer);
            }
            members.add(new Member(name, name.getBytes(StandardCharsets.UTF_8), entry.getValue()));
        }
        members.sort((a, b) -> Arrays.compareUnsigned(a.utf8, b.utf8));
        out.append('{');
        for (int i = 0; i < members.size(); i++) {
            Member member = members.get(i);
            if (i > 0) {
                out.append(',');
            }
            writeString(out, member.name, pointer);
            out.append(':');
            String child = pointer + "/" + ProtocolJson.escapePointerToken(member.name);
            writeValue(out, member.value, child, depth + 1);
        }
        out.append('}');
    }

    private record Member(String name, byte[] utf8, JsonElement value) {}

    private static String integerDigits(Number number, String pointer) {
        BigDecimal exact;
        if (number instanceof Double || number instanceof Float) {
            double d = number.doubleValue();
            if (!Double.isFinite(d)) {
                throw CanonicalJsonException.unrepresentable(
                        CanonicalJsonException.NON_FINITE_NUMBER, pointer);
            }
            exact = new BigDecimal(d);
        } else if (number instanceof BigDecimal decimal) {
            exact = decimal;
        } else if (number instanceof BigInteger integer) {
            exact = new BigDecimal(integer);
        } else if (number instanceof Long
                || number instanceof Integer
                || number instanceof Short
                || number instanceof Byte) {
            exact = BigDecimal.valueOf(number.longValue());
        } else {
            exact = parseNumber(number.toString(), pointer);
        }
        if (exact.signum() != 0 && exact.stripTrailingZeros().scale() > 0) {
            throw CanonicalJsonException.unrepresentable(
                    CanonicalJsonException.NON_INTEGER_NUMBER, pointer);
        }
        if (exact.abs().compareTo(MAX_SAFE) > 0) {
            throw CanonicalJsonException.unrepresentable(
                    CanonicalJsonException.INTEGER_OUT_OF_RANGE, pointer);
        }
        return exact.toBigIntegerExact().toString();
    }

    private static BigDecimal parseNumber(String literal, String pointer) {
        try {
            return new BigDecimal(literal);
        } catch (NumberFormatException e) {
            throw CanonicalJsonException.unrepresentable(
                    CanonicalJsonException.NON_FINITE_NUMBER, pointer);
        }
    }

    private static void writeString(StringBuilder out, String value, String pointer) {
        Objects.requireNonNull(value, "value");
        if (!isWellFormed(value)) {
            throw CanonicalJsonException.unrepresentable(
                    CanonicalJsonException.INVALID_UTF8, pointer);
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\t' -> out.append("\\t");
                case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f");
                case '\r' -> out.append("\\r");
                default -> {
                    if (c == 0) {
                        throw CanonicalJsonException.unrepresentable(
                                CanonicalJsonException.NUL_CHARACTER, pointer);
                    }
                    if (c < 0x20) {
                        out.append("\\u00").append(HEX.toHexDigits((byte) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** True if every surrogate is part of a pair (the string is encodable as UTF-8). */
    private static boolean isWellFormed(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)
                    && i + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return false;
            }
        }
        return true;
    }
}
