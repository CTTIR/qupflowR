/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bundled registry is a byte copy of the shared file and agrees with the HTTP statuses fixed by
 * the protocol contract (docs/06 error table and the SP01b transport additions). The expected
 * values below are transcribed from those documents, not from the registry.
 */
class BundledErrorCodesTest {

    /** docs/06 "Fehlercodes" table rows with a single status. */
    private static final Map<String, Integer> CONTRACT_STATUS =
            Map.ofEntries(
                    Map.entry("AUTH_REQUIRED", 401),
                    Map.entry("SCOPE_DENIED", 403),
                    Map.entry("SESSION_NOT_FOUND", 404),
                    Map.entry("OBJECT_NOT_FOUND", 404),
                    Map.entry("REVISION_CONFLICT", 409),
                    Map.entry("PROJECT_BUSY", 409),
                    Map.entry("IDEMPOTENCY_CONFLICT", 409),
                    Map.entry("SNAPSHOT_EXPIRED", 410),
                    Map.entry("PAYLOAD_TOO_LARGE", 413),
                    Map.entry("CALIBRATION_MISSING", 422),
                    Map.entry("IMAGE_UNAVAILABLE", 422),
                    Map.entry("CAPABILITY_UNAVAILABLE", 501),
                    Map.entry("RECOVERY_REQUIRED", 503),
                    Map.entry("HOST_CLOSING", 503),
                    Map.entry("INTERNAL_ERROR", 500));

    /** SP01b transport additions (BLUEPRINT section 5). */
    private static final Map<String, Integer> TRANSPORT_STATUS =
            Map.ofEntries(
                    Map.entry("MALFORMED_REQUEST", 400),
                    Map.entry("ENDPOINT_NOT_FOUND", 404),
                    Map.entry("METHOD_NOT_ALLOWED", 405),
                    Map.entry("REQUEST_TIMEOUT", 408),
                    Map.entry("LENGTH_REQUIRED", 411),
                    Map.entry("REQUEST_LINE_TOO_LONG", 414),
                    Map.entry("EXPECTATION_FAILED", 417),
                    Map.entry("HEADERS_TOO_LARGE", 431),
                    Map.entry("HTTP_VERSION_NOT_SUPPORTED", 505),
                    Map.entry("HOST_BUSY", 503));

    private static final Set<String> RETRYABLE = Set.of("REQUEST_TIMEOUT", "HOST_BUSY");

    private static final Set<String> NOT_RETRYABLE =
            Set.of(
                    "MALFORMED_REQUEST",
                    "AUTH_REQUIRED",
                    "SCOPE_DENIED",
                    "ENDPOINT_NOT_FOUND",
                    "METHOD_NOT_ALLOWED",
                    "LENGTH_REQUIRED",
                    "PAYLOAD_TOO_LARGE",
                    "REQUEST_LINE_TOO_LONG",
                    "EXPECTATION_FAILED",
                    "HEADERS_TOO_LARGE",
                    "HTTP_VERSION_NOT_SUPPORTED");

    @Test
    void bundledResourceIsAByteCopyOfTheSharedRegistry() throws IOException {
        Path source = Path.of(System.getProperty("qupflowr.test.errorCodesSource"));
        try (InputStream in =
                ErrorCodeRegistry.class.getResourceAsStream(ErrorCodeRegistry.RESOURCE)) {
            assertNotNull(in, "bundled registry resource");
            assertArrayEquals(Files.readAllBytes(source), in.readAllBytes());
        }
    }

    @Test
    void contractStatusesMatch() {
        ErrorCodeRegistry registry = ErrorCodeRegistry.bundled();
        CONTRACT_STATUS.forEach(
                (code, status) -> assertEquals(status, registry.require(code).httpStatus(), code));
        TRANSPORT_STATUS.forEach(
                (code, status) -> assertEquals(status, registry.require(code).httpStatus(), code));
    }

    @Test
    void retryHintsMatchTheTransportContract() {
        ErrorCodeRegistry registry = ErrorCodeRegistry.bundled();
        RETRYABLE.forEach(code -> assertTrue(registry.require(code).retryable(), code));
        NOT_RETRYABLE.forEach(
                code -> assertEquals(false, registry.require(code).retryable(), code));
    }

    @Test
    void everyEmittedCodeIsRegisteredWithAStatus() {
        ErrorCodeRegistry registry = ErrorCodeRegistry.bundled();
        Set<String> seen = new HashSet<>();
        for (String code : ErrorCodes.emitted()) {
            assertTrue(seen.add(code), "listed twice: " + code);
            assertTrue(registry.require(code).status().isPresent(), code);
        }
        assertEquals(List.copyOf(seen).size(), ErrorCodes.emitted().size());
    }
}
