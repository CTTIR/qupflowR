/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cttir.qupflowr.protocol.json.ProtocolJsonException;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

/** Registry parsing rules on small hand-written documents. */
class ErrorCodeRegistryTest {

    private static final String AUTH =
            "{\"code\":\"AUTH_REQUIRED\",\"http_status\":401,"
                    + "\"r_class\":\"qp_connection_error\",\"retryable\":false,"
                    + "\"message_key\":\"error.auth_required\","
                    + "\"source\":\"docs/06\"}";
    private static final String ENGINE =
            "{\"code\":\"BUNDLE_DIGEST_MISMATCH\",\"http_status\":null,"
                    + "\"r_class\":\"qp_bundle_error\",\"retryable\":false,"
                    + "\"message_key\":\"error.bundle_digest\"}";

    @Test
    void parsesArrayDocument() {
        ErrorCodeRegistry registry = ErrorCodeRegistry.parse("[" + AUTH + "," + ENGINE + "]");
        ErrorCode auth = registry.require("AUTH_REQUIRED");
        assertEquals(401, auth.httpStatus());
        assertEquals(OptionalInt.of(401), auth.status());
        assertEquals("qp_connection_error", auth.rClass());
        assertFalse(auth.retryable());
        assertEquals("error.auth_required", auth.messageKey());
        assertEquals("docs/06", auth.source());
        ErrorCode engine = registry.require("BUNDLE_DIGEST_MISMATCH");
        assertEquals(OptionalInt.empty(), engine.status());
        assertEquals(null, engine.source());
        assertEquals(
                List.of("AUTH_REQUIRED", "BUNDLE_DIGEST_MISMATCH"),
                registry.all().stream().map(ErrorCode::code).toList());
        assertTrue(registry.find("NOPE").isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.require("NOPE"));
    }

    @Test
    void parsesObjectDocumentWithCodesMember() {
        ErrorCodeRegistry registry =
                ErrorCodeRegistry.parse("{\"schema_version\":\"1.0\",\"codes\":[" + AUTH + "]}");
        assertEquals(1, registry.all().size());
    }

    @Test
    void rejectsDuplicateCodesWithPointer() {
        ProtocolJsonException e =
                assertThrows(
                        ProtocolJsonException.class,
                        () -> ErrorCodeRegistry.parse("[" + AUTH + "," + AUTH + "]"));
        assertEquals("/1/code", e.pointer());
    }

    @Test
    void rejectsInvalidEntries() {
        assertInvalid(AUTH.replace("401", "99"), "/0");
        assertInvalid(AUTH.replace("401", "600"), "/0");
        assertInvalid(AUTH.replace("401", "\"401\""), "/0/http_status");
        assertInvalid(AUTH.replace("401", "401.5"), "/0/http_status");
        assertInvalid(AUTH.replace(",\"http_status\":401", ""), "/0/http_status");
        assertInvalid(AUTH.replace("false", "\"no\""), "/0/retryable");
        assertInvalid(AUTH.replace("\"error.auth_required\"", "\" \""), "/0");
        assertInvalid(
                AUTH.replace(",\"message_key\":\"error.auth_required\"", ""), "/0/message_key");
        assertInvalid(AUTH.replace("qp_connection_error", "connection_error"), "/0");
        assertInvalid(AUTH.replace("AUTH_REQUIRED", "auth_required"), "/0");
        assertInvalid(AUTH.replace("AUTH_REQUIRED", "AUTH__REQUIRED"), "/0");
    }

    @Test
    void rejectsDocumentsWithoutEntries() {
        assertThrows(ProtocolJsonException.class, () -> ErrorCodeRegistry.parse("[]"));
        assertThrows(ProtocolJsonException.class, () -> ErrorCodeRegistry.parse("{\"codes\":{}}"));
        assertThrows(ProtocolJsonException.class, () -> ErrorCodeRegistry.parse("[1]"));
        assertThrows(ProtocolJsonException.class, () -> ErrorCodeRegistry.parse("\"x\""));
    }

    private static void assertInvalid(String entry, String pointer) {
        ProtocolJsonException e =
                assertThrows(
                        ProtocolJsonException.class,
                        () -> ErrorCodeRegistry.parse("[" + entry + "]"),
                        entry);
        assertEquals(pointer, e.pointer(), entry);
    }
}
