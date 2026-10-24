/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cttir.qupflowr.core.http.TransportCodes;
import io.github.cttir.qupflowr.protocol.ErrorCode;
import io.github.cttir.qupflowr.protocol.ErrorCodeRegistry;
import io.github.cttir.qupflowr.protocol.ErrorCodes;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * The statuses and retry hints the transport uses (written here from the transport rules of SP01b)
 * agree with the shared registry that renders the responses.
 */
class TransportCodesRegistryTest {

    private record Usage(int status, boolean retryable) {}

    private static final Map<String, Usage> TRANSPORT =
            Map.ofEntries(
                    Map.entry("MALFORMED_REQUEST", new Usage(400, false)),
                    Map.entry("SCOPE_DENIED", new Usage(403, false)),
                    Map.entry("REQUEST_TIMEOUT", new Usage(408, true)),
                    Map.entry("LENGTH_REQUIRED", new Usage(411, false)),
                    Map.entry("PAYLOAD_TOO_LARGE", new Usage(413, false)),
                    Map.entry("REQUEST_LINE_TOO_LONG", new Usage(414, false)),
                    Map.entry("EXPECTATION_FAILED", new Usage(417, false)),
                    Map.entry("HEADERS_TOO_LARGE", new Usage(431, false)),
                    Map.entry("INTERNAL_ERROR", new Usage(500, false)),
                    Map.entry("HOST_BUSY", new Usage(503, true)),
                    Map.entry("HTTP_VERSION_NOT_SUPPORTED", new Usage(505, false)));

    @Test
    void everyTransportCodeIsRegisteredWithTheTransportStatus() throws IllegalAccessException {
        Map<String, String> constants = new HashMap<>();
        for (Field field : TransportCodes.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                constants.put(field.getName(), (String) field.get(null));
            }
        }
        assertEquals(
                TRANSPORT.keySet(), constants.keySet(), "table covers every transport constant");
        ErrorCodeRegistry registry = ErrorCodeRegistry.bundled();
        for (Map.Entry<String, Usage> entry : TRANSPORT.entrySet()) {
            assertEquals(entry.getKey(), constants.get(entry.getKey()));
            ErrorCode code = registry.require(entry.getKey());
            assertEquals(entry.getValue().status(), code.httpStatus(), entry.getKey());
            assertEquals(entry.getValue().retryable(), code.retryable(), entry.getKey());
            // BridgeServer.start validates ErrorCodes.emitted() against the registry, so every
            // transport code must be part of that list to be checked before the first request.
            assertTrue(ErrorCodes.emitted().contains(entry.getKey()), entry.getKey());
        }
    }
}
