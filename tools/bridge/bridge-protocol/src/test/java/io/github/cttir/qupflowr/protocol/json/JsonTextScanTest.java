/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The string literal scan shared with {@code core_json_scan()}; expectations worked out by hand.
 */
class JsonTextScanTest {

    @Test
    void quotationMarksAfterAnOddRunOfReverseSolidiDoNotDelimit() {
        assertFalse(JsonTextScan.of("\"a\"").unterminated());
        assertTrue(JsonTextScan.of("[1]\"").unterminated());
        assertFalse(JsonTextScan.of("[\"a\\\\\"]").unterminated());
        assertTrue(JsonTextScan.of("[\"a\\\"]").unterminated());
        assertTrue(JsonTextScan.of("[\"a\\\\\\\"]").unterminated());
        assertFalse(JsonTextScan.of("[\"a\\\\\\\\\"]").unterminated());
        assertTrue(JsonTextScan.of("\\\"\"").unterminated());
        assertFalse(JsonTextScan.of("").unterminated());
    }

    @Test
    void maskingReplacesLiteralsAndTheUnterminatedTail() {
        assertEquals("[      ]", JsonTextScan.of("[\"a\\\"b\"]").maskedText());
        assertEquals("{   :1,   :  }", JsonTextScan.of("{\"a\":1,\"b\":\"\"}").maskedText());
        assertEquals("[1]    ", JsonTextScan.of("[1]\"1.5").maskedText());
        assertEquals("-54  ", JsonTextScan.of("-54\"5").maskedText());
        assertEquals("[ ]", JsonTextScan.of("[1]").maskedText().replace('1', ' '));
    }

    @Test
    void reservedCharactersAndBracketsCountOutsideLiteralsOnly() {
        assertTrue(JsonTextScan.of("{\"k\":\"v\"}/").hasReservedCharacterOutsideStrings());
        assertTrue(JsonTextScan.of("[1,\u000c2]").hasReservedCharacterOutsideStrings());
        assertFalse(JsonTextScan.of("[\"/\u000b\u000c\"]").hasReservedCharacterOutsideStrings());
        assertFalse(JsonTextScan.of("[\"a\"]\"/").hasReservedCharacterOutsideStrings());
        String nested = "[[[\"[[[\"]]]";
        assertTrue(JsonTextScan.of(nested).nestingExceeds(2));
        assertFalse(JsonTextScan.of(nested).nestingExceeds(3));
        assertFalse(JsonTextScan.of("\"[[[[").nestingExceeds(0));
        assertTrue(JsonTextScan.of("[[\"").nestingExceeds(1));
    }

    @Test
    void escapesAreCheckedInsideCompleteLiteralsOnly() {
        JsonTextScan.of("[\"\\ud83d\\ude00\"]").checkEscapes();
        JsonTextScan.of("[\"\\\\u0000\"]").checkEscapes();
        JsonTextScan.of("[1]\"\\u0000").checkEscapes();
        JsonTextScan.of("[\"\\u00\"]").checkEscapes();
        JsonTextScan.of("\\u0000").checkEscapes();
        assertEquals(
                "nul_character",
                assertThrows(
                                CanonicalJsonException.class,
                                () -> JsonTextScan.of("[\"\\ud800\",\"\\u0000\"]").checkEscapes())
                        .reason());
        assertEquals(
                "unpaired_surrogate",
                assertThrows(
                                CanonicalJsonException.class,
                                () -> JsonTextScan.of("[\"\\uDBFF\\uDBFF\"]").checkEscapes())
                        .reason());
        assertEquals(
                "unpaired_surrogate",
                assertThrows(
                                CanonicalJsonException.class,
                                () -> JsonTextScan.of("[\"\\udc00\"]").checkEscapes())
                        .reason());
        // An escaped reverse solidus between the halves separates them.
        assertEquals(
                "unpaired_surrogate",
                assertThrows(
                                CanonicalJsonException.class,
                                () -> JsonTextScan.of("[\"\\ud800\\\\\\udc00\"]").checkEscapes())
                        .reason());
        assertEquals(
                "unpaired_surrogate",
                assertThrows(
                                CanonicalJsonException.class,
                                () -> JsonTextScan.of("[\"\\ud800x\\udc00\"]").checkEscapes())
                        .reason());
    }
}
