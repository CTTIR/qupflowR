/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import io.github.cttir.qupflowr.protocol.dto.CapabilitiesData;
import io.github.cttir.qupflowr.protocol.dto.Capability;
import io.github.cttir.qupflowr.protocol.dto.HealthData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class ProtocolJsonTest {

    @Test
    void writesSnakeCaseMembersAndExplicitNulls() {
        HealthData health =
                new HealthData("ready", "worker", "0.7.0", "0.1.0", "1.0", "25.0.4.1", null);
        assertEquals(
                "{\"status\":\"ready\",\"host_type\":\"worker\",\"qupath_version\":\"0.7.0\","
                        + "\"bridge_version\":\"0.1.0\",\"protocol_version\":\"1.0\","
                        + "\"java_version\":\"25.0.4.1\","
                        + "\"fx_thread_blocked\":null}",
                ProtocolJson.toJson(health));
    }

    @Test
    void doesNotEscapeHtmlAndKeepsUnicode() {
        assertEquals(
                "{\"a\":\"<b>&'=\",\"u\":\"Zürich μm\"}",
                ProtocolJson.toJson(ordered("a", "<b>&'=", "u", "Zürich μm")));
    }

    @Test
    void writesLargeIntegersExactly() {
        assertEquals(
                "{\"n\":9007199254740993,\"d\":0.1}",
                ProtocolJson.toJson(
                        ordered("n", 9_007_199_254_740_993L, "d", new BigDecimal("0.1"))));
    }

    @Test
    void rejectsNonFiniteDoubleInsideDtoWithPointer() {
        Capability capability =
                Capability.available(
                        "bridge.health",
                        List.of("worker"),
                        List.of("0.7.0"),
                        ordered("ratio", Double.NaN));
        CapabilitiesData data = new CapabilitiesData("worker", List.of(capability));
        NonFiniteNumberException e =
                assertThrows(NonFiniteNumberException.class, () -> ProtocolJson.toJson(data));
        assertEquals("/capabilities/0/limits/ratio", e.pointer());
    }

    @Test
    void rejectsInfiniteFloatsAndEscapesPointerTokens() {
        assertEquals(
                "/a~1b~0c",
                assertThrows(
                                NonFiniteNumberException.class,
                                () ->
                                        ProtocolJson.toJson(
                                                ordered("a/b~c", Float.POSITIVE_INFINITY)))
                        .pointer());
        assertEquals(
                "/x/1",
                assertThrows(
                                NonFiniteNumberException.class,
                                () ->
                                        ProtocolJson.toJson(
                                                ordered("x", List.of(1, Double.NEGATIVE_INFINITY))))
                        .pointer());
    }

    @Test
    void rejectsNonFiniteJsonPrimitivesInTrees() {
        JsonObject object = new JsonObject();
        JsonArray array = new JsonArray();
        array.add(new JsonPrimitive(Double.NaN));
        object.add("values", array);
        NonFiniteNumberException e =
                assertThrows(NonFiniteNumberException.class, () -> ProtocolJson.toJson(object));
        assertEquals("/values/0", e.pointer());
        assertThrows(NonFiniteNumberException.class, () -> ProtocolJson.toPrettyJson(object));
    }

    @Test
    void strictGsonAlsoRefusesNonFiniteNumbers() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProtocolJson.gson().toJson(new JsonPrimitive(Double.NaN)));
    }

    @Test
    void prettyOutputUsesTwoSpaces() {
        assertEquals(
                "{\n  \"a\": [\n    1\n  ]\n}",
                ProtocolJson.toPrettyJson(ordered("a", List.of(1))));
    }

    @Test
    void parsesValidDocumentsWithExactNumbers() {
        JsonElement tree =
                ProtocolJson.parse(
                        "{\"a\":[1,2.5,-0.0,12345678901234567890.5],\"b\":null,\"c\":true,"
                                + "\"d\":\"\\u00e9\\n\"}");
        JsonArray a = tree.getAsJsonObject().getAsJsonArray("a");
        assertEquals(new BigDecimal("12345678901234567890.5"), a.get(3).getAsBigDecimal());
        assertTrue(tree.getAsJsonObject().get("b").isJsonNull());
        assertEquals("é\n", tree.getAsJsonObject().get("d").getAsString());
    }

    @Test
    void rejectsDuplicateKeysWithPointer() {
        assertEquals(
                "/a",
                assertThrows(
                                ProtocolJsonException.class,
                                () -> ProtocolJson.parse("{\"a\":1,\"a\":2}"))
                        .pointer());
        assertEquals(
                "/x/0/k~1v",
                assertThrows(
                                ProtocolJsonException.class,
                                () -> ProtocolJson.parse("{\"x\":[{\"k/v\":1,\"k/v\":1}]}"))
                        .pointer());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "NaN",
                "{\"a\":NaN}",
                "[Infinity]",
                "[-Infinity]",
                "{\"a\":1}x",
                "{\"a\":1} {\"b\":2}",
                "// c\n{}",
                "{'a':1}",
                "{a:1}",
                "[1,]",
                "{\"a\":1,}",
                "[01]",
                "\"tab\there\"",
                "\"\\x\""
            })
    void rejectsMalformedOrLenientInput(String text) {
        assertThrows(ProtocolJsonException.class, () -> ProtocolJson.parse(text));
    }

    @Test
    void rejectsByteOrderMark() {
        assertThrows(ProtocolJsonException.class, () -> ProtocolJson.parse((char) 0xFEFF + "{}"));
    }

    @Test
    void boundsNestingDepthLikeTheSharedReader() {
        // 128 nested arrays: the innermost array has depth 127 and is accepted; one more level
        // puts a value at depth 128, which R (qp_json_decode) and CanonicalJsonReader reject.
        int max = ProtocolJson.MAX_DEPTH;
        assertEquals(128, max);
        String ok = "[".repeat(max) + "]".repeat(max);
        String tooDeep = "[".repeat(max + 1) + "]".repeat(max + 1);
        ProtocolJson.parse(ok);
        assertReason("JSON_INVALID", "nesting_too_deep", tooDeep);
        String scalarTooDeep = "[".repeat(max) + "1" + "]".repeat(max);
        assertReason("JSON_INVALID", "nesting_too_deep", scalarTooDeep);
    }

    /**
     * Documents the Gson-based parser accepted before it delegated to the shared reader; each is
     * rejected by qp_json_decode() in R with the same code and reason.
     */
    @Test
    void rejectsWhatTheSharedDecodingRulesReject() {
        assertReason("JSON_UNREPRESENTABLE", "empty_member_name", "{\"\":1}");
        assertReason("JSON_UNREPRESENTABLE", "nul_character", "[\"\\u0000\"]");
        assertReason("JSON_INVALID", "unpaired_surrogate", "[\"\\ud800\"]");
        assertReason("JSON_UNREPRESENTABLE", "number_out_of_range", "[1e400]");
        assertReason("JSON_INVALID", "syntax", "[1 /* c */]");
        assertReason("JSON_INVALID", "syntax", "[1,\f2]");
        assertReason("JSON_INVALID", "byte_order_mark", (char) 0xFEFF + "{}");
        assertReason("JSON_INVALID", "duplicate_key", "{\"a\":1,\"a\":2}");
    }

    private static void assertReason(String code, String reason, String text) {
        CanonicalJsonException e =
                assertThrows(CanonicalJsonException.class, () -> ProtocolJson.parse(text));
        assertEquals(code + " " + reason, e.code() + " " + e.reason(), text);
    }

    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
