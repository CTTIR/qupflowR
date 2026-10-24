/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import static io.github.cttir.qupflowr.protocol.json.CanonicalJsonException.JSON_INVALID;
import static io.github.cttir.qupflowr.protocol.json.CanonicalJsonException.JSON_UNREPRESENTABLE;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Decoding rules shared with {@code qp_json_decode()}. Expected codes, reasons and pointers are the
 * results of the R package for the same text (its {@code test-json.R} table plus further cases run
 * through R 4.6.1 while this reader was written).
 */
class CanonicalJsonReaderTest {

    private static CanonicalJsonException rejected(String text) {
        return assertThrows(CanonicalJsonException.class, () -> CanonicalJsonReader.read(text));
    }

    private static CanonicalJsonException rejected(byte[] bytes) {
        return assertThrows(CanonicalJsonException.class, () -> CanonicalJsonReader.read(bytes));
    }

    private static String nest(int n, String inner) {
        return "[".repeat(n) + inner + "]".repeat(n);
    }

    @Test
    void theRDecodingTableIsReproduced() {
        record Case(String text, String code, String reason, String pointer) {}
        for (Case c :
                new Case[] {
                    new Case("{\"a\":1,\"a\":2}", JSON_INVALID, "duplicate_key", "/a"),
                    new Case("{\"\":1}", JSON_UNREPRESENTABLE, "empty_member_name", "/"),
                    new Case("[NaN]", JSON_INVALID, "syntax", ""),
                    new Case("[1] x", JSON_INVALID, "syntax", ""),
                    new Case("[1e400]", JSON_UNREPRESENTABLE, "number_out_of_range", "/0"),
                    new Case("[-1e400]", JSON_UNREPRESENTABLE, "number_out_of_range", "/0"),
                    new Case("[\"\\u0000\"]", JSON_UNREPRESENTABLE, "nul_character", ""),
                    new Case("[\"\\ud800\"]", JSON_INVALID, "unpaired_surrogate", ""),
                    new Case("[\"\\udc00\\ud800\"]", JSON_INVALID, "unpaired_surrogate", ""),
                    new Case("[\"\\ud800\\u0041\"]", JSON_INVALID, "unpaired_surrogate", ""),
                    new Case("[\"\\ud83d\\ude00\\udc00\"]", JSON_INVALID, "unpaired_surrogate", ""),
                    new Case("\uFEFF[1]", JSON_INVALID, "byte_order_mark", ""),
                    new Case(
                            "[1.7976931348623159e308]",
                            JSON_UNREPRESENTABLE,
                            "number_out_of_range",
                            "/0"),
                    new Case(
                            "{\"a\":[1e400],\"\":1}",
                            JSON_UNREPRESENTABLE,
                            "empty_member_name",
                            "/"),
                    new Case(
                            "{\"a\":[1e400],\"b\":{\"\":1}}",
                            JSON_UNREPRESENTABLE,
                            "number_out_of_range",
                            "/a/0"),
                    new Case("[{\"a\":1,\"a\":2},1e400]", JSON_INVALID, "duplicate_key", "/0/a"),
                    new Case(
                            "[1e400,{\"a\":1,\"a\":2}]",
                            JSON_UNREPRESENTABLE,
                            "number_out_of_range",
                            "/0"),
                    new Case(
                            "{\"a\":{\"\":1,\"b\":2,\"b\":3}}",
                            JSON_UNREPRESENTABLE,
                            "empty_member_name",
                            "/a/"),
                    new Case(
                            "{\"b\":[1,{\"c\":1,\"c\":2}],\"a\":{\"x\":1,\"x\":1}}",
                            JSON_INVALID,
                            "duplicate_key",
                            "/b/1/c"),
                    new Case(
                            "{\"a~b/c\":1,\"a~b/c\":2}", JSON_INVALID, "duplicate_key", "/a~0b~1c"),
                    new Case("[\"\\u0000\"", JSON_UNREPRESENTABLE, "nul_character", ""),
                    new Case("[\"\\ud800\"", JSON_INVALID, "unpaired_surrogate", ""),
                    new Case(
                            "{\"a\":\"\\ud800\", \"a\":1}", JSON_INVALID, "unpaired_surrogate", ""),
                }) {
            CanonicalJsonException e = rejected(c.text());
            assertEquals(c.code(), e.code(), c.text());
            assertEquals(c.reason(), e.reason(), c.text());
            assertEquals(c.pointer(), e.pointer(), c.text());
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[\"a\tb\"]",
                "[\"a\u0001b\"]",
                "[01]",
                "[1.]",
                "[.5]",
                "[-]",
                "[+1]",
                "[0x10]",
                "[\"\\x\"]",
                "[\"\\U0041\"]",
                "[1,]",
                "{\"a\":1,}",
                "",
                " ",
                "[true false]",
                "[1] [2]",
                "{\"a\" 1}",
                "[nan]",
                "[Infinity]",
                "[\"a\"\"b\"]",
                "{1:2}",
                "[tru]",
                "[nul]",
                "[\"abc]",
                "[\"\\u12\"]",
                "[\"\\u12g4\"]",
                "[1]]",
                "[[1]",
                "{\"a\":}",
                "[1e]",
                "[1e+]",
                "[-01]",
                "[00]",
                "[truefalse]",
                "[null1]",
                "[1true]",
                "[\"a\"1]",
                "{\"a\":1}{\"b\":2}",
                "#c\n[1]",
                "[1-2]",
                "[1,2",
                "[\"\\u00e９\"]"
            })
    void grammarViolationsAreSyntaxErrors(String text) {
        CanonicalJsonException e = rejected(text);
        assertEquals(JSON_INVALID, e.code(), text);
        assertEquals("syntax", e.reason(), text);
    }

    /**
     * A complete value followed by an unterminated string. yajl, the parser behind the R reader,
     * accepts these; the shared scan before parsing rejects every unterminated string.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "[1]\"",
                "[1]\"abc",
                "{\"a\":1}\"x",
                "1 \"",
                "1\"",
                "true\"",
                "null\"abc",
                "\"a\"\"",
                "-54\"5",
                "[1]\n\"abc",
                "[1] \" ",
                "[1]\"a\\\"",
                "[1]\"\\\\",
                "[1]\"\\ud800",
                "[1]\"\\u0000",
                "[\"\\u0000",
                "[\"\\ud800"
            })
    void unterminatedStringsAreSyntaxErrors(String text) {
        CanonicalJsonException e = rejected(text);
        assertEquals(JSON_INVALID, e.code(), text);
        assertEquals("syntax", e.reason(), text);
        assertEquals(
                "syntax",
                assertThrows(CanonicalJsonException.class, () -> ProtocolJson.parse(text)).reason(),
                text);
        assertEquals(
                "syntax",
                assertThrows(CanonicalJsonException.class, () -> CanonicalJson.canonicalize(text))
                        .reason(),
                text);
    }

    @Test
    void surrogateEscapesPairOnlyWhenTheyFollowEachOtherInTheText() {
        assertEquals("unpaired_surrogate", rejected("[\"\\ud800\\\\\\udc00\"]").reason());
        assertEquals("unpaired_surrogate", rejected("[\"\\ud800\\u0041\"]").reason());
        assertEquals("unpaired_surrogate", rejected("[\"\\ud800\\ud800\\udc00\"]").reason());
        // The escape scan runs before the bracket depth scan.
        String deep = "[\"\\ud800\\\\\\udc00\"," + "[".repeat(300);
        assertEquals("unpaired_surrogate", rejected(deep).reason());
        assertEquals("nul_character", rejected("[\"\\\\\\u0000\"]").reason());
        assertEquals(
                "\\ud800\\u0000",
                CanonicalJsonReader.read("[\"\\\\ud800\\\\u0000\"]")
                        .getAsJsonArray()
                        .get(0)
                        .getAsString());
        JsonArray quotes = CanonicalJsonReader.read("[\"\\\\\\\"\", \"a\\\\\"]").getAsJsonArray();
        assertEquals("\\\"", quotes.get(0).getAsString());
        assertEquals("a\\", quotes.get(1).getAsString());
    }

    @Test
    void unterminatedStringsHideBracketsAndNumberTokens() {
        assertEquals("nesting_too_deep", rejected("[".repeat(300) + "\"").reason());
        assertEquals("syntax", rejected("[1]\"" + "[".repeat(300)).reason());
        assertEquals(
                "syntax",
                assertThrows(
                                CanonicalJsonException.class,
                                () -> CanonicalJson.canonicalize("[1]\"1.5"))
                        .reason());
        assertEquals(
                "non_integer_number",
                assertThrows(
                                CanonicalJsonException.class,
                                () -> CanonicalJson.canonicalize("[\"\\\\\\\"\", 1.5]"))
                        .reason());
    }

    /**
     * 1 MiB of escaped quotation marks without a closing one. A backtracking literal pattern needs
     * minutes for this text; the linear scan needs milliseconds.
     */
    @Test
    void scansBeforeParsingTakeLinearTime() {
        String unterminated = "[\"" + "\\\"".repeat(524_287);
        assertEquals(1 << 20, unterminated.length());
        Duration limit = Duration.ofSeconds(1);
        assertTimeoutPreemptively(
                limit, () -> assertEquals("syntax", rejected(unterminated).reason()));
        assertTimeoutPreemptively(
                limit,
                () ->
                        assertEquals(
                                "syntax",
                                assertThrows(
                                                CanonicalJsonException.class,
                                                () -> ProtocolJson.parse(unterminated))
                                        .reason()));
        assertTimeoutPreemptively(
                limit,
                () ->
                        assertEquals(
                                "syntax",
                                assertThrows(
                                                CanonicalJsonException.class,
                                                () -> CanonicalJson.canonicalize(unterminated))
                                        .reason()));
        String terminated = unterminated + "\"]";
        assertTimeoutPreemptively(
                limit,
                () ->
                        assertEquals(
                                "\"".repeat(524_287),
                                CanonicalJsonReader.read(terminated)
                                        .getAsJsonArray()
                                        .get(0)
                                        .getAsString()));
        String escapes = "[\"" + "\\ud83d\\ude00".repeat(87_381) + "\"]";
        String emoji = new String(Character.toChars(0x1F600));
        assertTimeoutPreemptively(
                limit,
                () ->
                        assertEquals(
                                emoji.repeat(87_381),
                                CanonicalJsonReader.read(escapes)
                                        .getAsJsonArray()
                                        .get(0)
                                        .getAsString()));
    }

    /**
     * RFC 8259 has no comments and only space, tab, line feed and carriage return as white space.
     * The R reader (jsonlite) currently accepts comments and form feed or vertical tab between
     * tokens; this divergence is reported for the R side instead of being copied here.
     */
    @ParameterizedTest
    @ValueSource(
            strings = {"[1 /* c */]", "[1 // c\n]", "[1]//c", "[1]/*c*/", "[1,\f2]", "[1,\u000b2]"})
    void commentsAndNonJsonWhiteSpaceAreRejected(String text) {
        assertEquals("syntax", rejected(text).reason(), text);
    }

    @Test
    void encodingProblemsOfTheBytesAreRejected() {
        byte[][] invalid = {
            {0x5b, 0x22, (byte) 0xff, 0x22, 0x5d},
            {0x5b, (byte) 0xed, (byte) 0xa0, (byte) 0x80, 0x5d},
            {0x5b, 0x22, (byte) 0xc0, (byte) 0x80, 0x22, 0x5d},
            {0x5b, 0x22, (byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80, 0x22, 0x5d}
        };
        for (byte[] bytes : invalid) {
            assertEquals("invalid_utf8", rejected(bytes).reason());
        }
        assertEquals("nul_byte", rejected(new byte[] {0x5b, 0x00, 0x5d}).reason());
        byte[] bom = {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 0x5b, 0x5d};
        assertEquals("byte_order_mark", rejected(bom).reason());
        assertEquals("invalid_utf8", rejected("[\"\ud800\"]").reason());
        assertEquals("nul_byte", rejected("[\"\u0000\"]").reason());
        byte[] maxCodePoint = {
            0x5b, 0x22, (byte) 0xf4, (byte) 0x8f, (byte) 0xbf, (byte) 0xbf, 0x22, 0x5d
        };
        assertEquals(
                new String(Character.toChars(0x10FFFF)),
                CanonicalJsonReader.read(maxCodePoint).getAsJsonArray().get(0).getAsString());
    }

    @Test
    void nestingIsLimitedTo128Levels() {
        assertEquals(128, depth(CanonicalJsonReader.read(nest(128, ""))));
        assertEquals("nesting_too_deep", rejected(nest(129, "")).reason());
        assertEquals("nesting_too_deep", rejected(nest(128, "1")).reason());
        assertEquals(127, depth(CanonicalJsonReader.read(nest(127, "1"))));
        assertEquals("syntax", rejected("[".repeat(129)).reason());
        CanonicalJsonException scan = rejected("[".repeat(300));
        assertEquals(JSON_INVALID, scan.code());
        assertEquals("nesting_too_deep", scan.reason());
        String objects = "{\"a\":".repeat(128) + "1" + "}".repeat(128);
        assertEquals("nesting_too_deep", rejected(objects).reason());
        String shallower = "{\"a\":".repeat(127) + "1" + "}".repeat(127);
        CanonicalJsonReader.read(shallower);
        String deepString = "[\"" + "[".repeat(300) + "\"]";
        assertEquals(
                "[".repeat(300),
                CanonicalJsonReader.read(deepString).getAsJsonArray().get(0).getAsString());
        assertEquals("syntax", rejected("[" + " ".repeat(300) + "]]").reason());
    }

    private static int depth(JsonElement e) {
        if (e.isJsonArray() && !e.getAsJsonArray().isEmpty()) {
            return 1 + depth(e.getAsJsonArray().get(0));
        }
        return e.isJsonArray() ? 1 : 0;
    }

    @Test
    void acceptedTextKeepsValuesExactly() {
        String numberText = "{\"n\":[9007199254740993,-0,1.0,1e-400,2147483648,1E+2],";
        String escapes = "\"s\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u00E9\\ud83d\\ude00";
        String literal = "\u007f\u2028\",";
        String rest = "\"e\":{},\"a\":[],\"z\":[null,true,false]}";
        JsonElement value =
                CanonicalJsonReader.read(" \t\r\n" + numberText + escapes + literal + rest + " \n");
        JsonObject object = value.getAsJsonObject();
        JsonArray numbers = object.getAsJsonArray("n");
        assertEquals(new BigDecimal("9007199254740993"), numbers.get(0).getAsBigDecimal());
        assertEquals(0, numbers.get(1).getAsBigDecimal().signum());
        assertEquals(new BigDecimal("1.0"), numbers.get(2).getAsBigDecimal());
        assertEquals(0, Double.compare(0.0, numbers.get(3).getAsDouble()));
        assertEquals(new BigDecimal("2147483648"), numbers.get(4).getAsBigDecimal());
        assertEquals(new BigDecimal("1E+2"), numbers.get(5).getAsBigDecimal());
        assertEquals(
                "\"\\/\b\f\n\r\té" + new String(Character.toChars(0x1F600)) + "\u007f\u2028",
                object.get("s").getAsString());
        assertEquals(new JsonObject(), object.get("e"));
        assertEquals(new JsonArray(), object.get("a"));
        JsonArray literals = new JsonArray();
        literals.add(JsonNull.INSTANCE);
        literals.add(new JsonPrimitive(true));
        literals.add(new JsonPrimitive(false));
        assertEquals(literals, object.get("z"));
        assertEquals(
                "\\ud800",
                CanonicalJsonReader.read("[\"\\\\ud800\"]").getAsJsonArray().get(0).getAsString());
        assertEquals(
                "\\u0000",
                CanonicalJsonReader.read("[\"\\\\u0000\"]".getBytes(StandardCharsets.UTF_8))
                        .getAsJsonArray()
                        .get(0)
                        .getAsString());
        assertEquals(new JsonPrimitive("x"), CanonicalJsonReader.read("\"x\""));
        assertEquals(JsonNull.INSTANCE, CanonicalJsonReader.read("null"));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "[1.7976931348623158e308]|1.7976931348623157E308",
                "[4.9e-324]|4.9E-324",
                "[2.4703282292062328e-324]|4.9E-324",
                "[2.4703282292062327e-324]|0.0",
                "[1e-9999999999]|0.0"
            })
    void numbersAtTheEdgeOfTheDoubleRangeFollowCorrectRounding(String text, String expected) {
        assertEquals(
                Double.parseDouble(expected),
                CanonicalJsonReader.read(text).getAsJsonArray().get(0).getAsDouble());
    }
}
