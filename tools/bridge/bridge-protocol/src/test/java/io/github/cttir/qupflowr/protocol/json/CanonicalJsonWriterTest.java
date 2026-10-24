/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.internal.LazilyParsedNumber;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Canonical JSON of values and text. Expected strings come from the R package's {@code
 * test-json-canonical.R} and from R 4.6.1 runs of the same inputs.
 */
class CanonicalJsonWriterTest {

    private static CanonicalJsonException rejected(JsonElement value) {
        return assertThrows(CanonicalJsonException.class, () -> CanonicalJson.write(value));
    }

    private static CanonicalJsonException rejectedText(String text) {
        return assertThrows(CanonicalJsonException.class, () -> CanonicalJson.canonicalize(text));
    }

    @Test
    void valuesCanonicaliseWithSortedKeysAndNoWhitespace() {
        JsonObject alpha = new JsonObject();
        JsonArray b = new JsonArray();
        b.add(3);
        b.add(1);
        alpha.add("b", b);
        alpha.add("a", new JsonObject());
        JsonArray list = new JsonArray();
        list.add("x");
        list.add(JsonNull.INSTANCE);
        list.add(new JsonArray());
        JsonObject x = new JsonObject();
        x.addProperty("zeta", 1);
        x.add("alpha", alpha);
        x.addProperty("é", true);
        x.add("Z", JsonNull.INSTANCE);
        x.add("list", list);
        assertEquals(
                "{\"Z\":null,\"alpha\":{\"a\":{},\"b\":[3,1]},\"list\":[\"x\",null,[]],"
                        + "\"zeta\":1,\"é\":true}",
                CanonicalJson.write(x));
    }

    @Test
    void keyOrderIsByCodePointNotUtf16OrLocale() {
        List<String> keys =
                List.of(new String(Character.toChars(0x1F600)), "ﬁ", "B", "a", "ä", "ab", "A");
        JsonObject x = new JsonObject();
        for (int i = 0; i < keys.size(); i++) {
            x.addProperty(keys.get(i), i + 1);
        }
        assertEquals(
                "{\"A\":7,\"B\":3,\"a\":4,\"ab\":6,\"ä\":5,\"ﬁ\":2,\""
                        + new String(Character.toChars(0x1F600))
                        + "\":1}",
                CanonicalJson.write(x));
    }

    @Test
    void numbersAreIntegersWithin53Bits() {
        JsonArray numbers = new JsonArray();
        numbers.add(9007199254740991.0);
        numbers.add(-9007199254740991L);
        numbers.add(-0.0);
        numbers.add(5e9);
        numbers.add(new BigDecimal("1.0"));
        numbers.add(new BigDecimal("1E+2"));
        numbers.add(new BigInteger("-12"));
        numbers.add(new LazilyParsedNumber("7"));
        assertEquals(
                "[9007199254740991,-9007199254740991,0,5000000000,1,100,-12,7]",
                CanonicalJson.write(numbers));
        record Case(JsonElement value, String reason, String pointer) {}
        JsonObject a = new JsonObject();
        JsonArray fraction = new JsonArray();
        fraction.add(1);
        fraction.add(1.25);
        a.add("a", fraction);
        JsonObject sorted = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("z", 0.5);
        inner.addProperty("a", 0.25);
        sorted.add("b", inner);
        for (Case c :
                List.of(
                        new Case(new JsonPrimitive(0.5), "non_integer_number", ""),
                        new Case(new JsonPrimitive(9007199254740992.0), "integer_out_of_range", ""),
                        new Case(new JsonPrimitive(Long.MAX_VALUE), "integer_out_of_range", ""),
                        new Case(
                                new JsonPrimitive(new BigInteger("1" + "0".repeat(30))),
                                "integer_out_of_range",
                                ""),
                        new Case(a, "non_integer_number", "/a/1"),
                        new Case(sorted, "non_integer_number", "/b/a"),
                        new Case(new JsonPrimitive(Double.NaN), "non_finite_number", ""),
                        new Case(
                                new JsonPrimitive(Double.POSITIVE_INFINITY),
                                "non_finite_number",
                                ""),
                        new Case(
                                new JsonPrimitive(new LazilyParsedNumber("NaN")),
                                "non_finite_number",
                                ""))) {
            CanonicalJsonException e = rejected(c.value());
            assertEquals(CanonicalJsonException.JSON_UNREPRESENTABLE, e.code());
            assertEquals(c.reason(), e.reason(), String.valueOf(c.value()));
            assertEquals(c.pointer(), e.pointer(), String.valueOf(c.value()));
        }
    }

    @Test
    void textNumbersMustBeIntegerTokens() {
        assertEquals("[0,12]", CanonicalJson.canonicalize("[-0, 12]"));
        assertEquals("[\"1.5\",\"2e3\"]", CanonicalJson.canonicalize("[\"1.5\", \"2e3\"]"));
        assertEquals(
                "{\"a\":[1,\"2.5\"],\"b\":\"1e5\"}",
                CanonicalJson.canonicalize("{\"b\":\"1e5\",\"a\":[1, \"2.5\"]}"));
        assertEquals("[-9007199254740991]", CanonicalJson.canonicalize("[-9007199254740991]"));
        record Case(String text, String code, String reason) {}
        String unrepresentable = CanonicalJsonException.JSON_UNREPRESENTABLE;
        String invalid = CanonicalJsonException.JSON_INVALID;
        for (Case c :
                List.of(
                        new Case("[-9007199254740992]", unrepresentable, "integer_out_of_range"),
                        new Case("[12345678901234567]", unrepresentable, "integer_out_of_range"),
                        new Case("[-12345678901234567]", unrepresentable, "integer_out_of_range"),
                        new Case("[1E+2]", unrepresentable, "non_integer_number"),
                        new Case("[0.0]", unrepresentable, "non_integer_number"),
                        new Case("{\"a\":1.5,\"a\":2}", unrepresentable, "non_integer_number"),
                        new Case("[9007199254740992, 1.5]", unrepresentable, "non_integer_number"),
                        new Case("[\"\\ud800\", 1.5]", unrepresentable, "non_integer_number"),
                        new Case("[\"a\\\"\" 1.5]", unrepresentable, "non_integer_number"),
                        new Case("[1e400]", unrepresentable, "non_integer_number"),
                        new Case("[\"\\u0000\"]", unrepresentable, "nul_character"),
                        new Case("[\"\\ud800\", 1]", invalid, "unpaired_surrogate"),
                        new Case("[0001]", invalid, "syntax"),
                        new Case("\uFEFF[1.5]", invalid, "byte_order_mark"),
                        new Case("[\"abc", invalid, "syntax"),
                        new Case("[1,2", invalid, "syntax"),
                        new Case("[1-2]", invalid, "syntax"))) {
            CanonicalJsonException e = rejectedText(c.text());
            assertEquals(c.code(), e.code(), c.text());
            assertEquals(c.reason(), e.reason(), c.text());
        }
    }

    @Test
    void stringsUseMinimalEscaping() {
        assertEquals(
                "{\"a\":\"\\u0001\\u001f\u007f\u2028/\"}",
                CanonicalJson.canonicalize("{\"a\":\"\\u0001\\u001F\\u007f\\u2028/\"}"));
        assertEquals(
                "[\"\\\"\\\\\\b\\t\\n\\f\\r\\u000b\"]",
                CanonicalJson.write(parseArray("\\\"\\\\\\b\\t\\n\\f\\r\\u000b")));
        JsonArray nul = new JsonArray();
        nul.add("a\u0000b");
        assertEquals("nul_character", rejected(nul).reason());
        assertEquals("/0", rejected(nul).pointer());
        JsonArray lone = new JsonArray();
        lone.add("a\ud800");
        assertEquals("invalid_utf8", rejected(lone).reason());
    }

    private static JsonElement parseArray(String escapedContent) {
        return CanonicalJsonReader.read("[\"" + escapedContent + "\"]");
    }

    @Test
    void memberNamesAndNestingAreChecked() {
        JsonObject x = new JsonObject();
        JsonObject inner = new JsonObject();
        inner.addProperty("", 1);
        x.add("x", inner);
        CanonicalJsonException empty = rejected(x);
        assertEquals("empty_member_name", empty.reason());
        assertEquals("/x", empty.pointer());
        JsonObject lone = new JsonObject();
        lone.addProperty("\udc00", 1);
        assertEquals("invalid_utf8", rejected(lone).reason());
        JsonElement deep = new JsonArray();
        for (int i = 0; i < 128; i++) {
            JsonArray wrapper = new JsonArray();
            wrapper.add(deep);
            deep = wrapper;
        }
        CanonicalJsonException tooDeep = rejected(deep);
        assertEquals(CanonicalJsonException.JSON_UNREPRESENTABLE, tooDeep.code());
        assertEquals("nesting_too_deep", tooDeep.reason());
        assertEquals("/0".repeat(128), tooDeep.pointer());
        CanonicalJson.write(deep.getAsJsonArray().get(0));
        assertEquals("null", CanonicalJson.write(null));
    }

    @Test
    void digestsUseTheCanonicalBytes() throws Exception {
        Path vector =
                Path.of(
                        System.getProperty("qupflowr.test.schemaDir"),
                        "protocol",
                        "v1",
                        "fixtures",
                        "canonical",
                        "basic-object.sha256");
        String expected = Files.readString(vector, StandardCharsets.US_ASCII).split(" ")[0];
        JsonObject value = new JsonObject();
        value.addProperty("b", 2);
        value.addProperty("a", 1);
        assertEquals(expected, CanonicalJson.sha256Hex(value));
        assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                CanonicalJson.sha256Hex(new byte[0]));
    }
}
