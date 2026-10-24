/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.cttir.qupflowr.protocol.ErrorCode;
import io.github.cttir.qupflowr.protocol.json.ProtocolJson;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class DtoRulesTest {

    @Test
    void errorEnvelopeHasTheContractShape() {
        ErrorCode entry =
                new ErrorCode(
                        "AUTH_REQUIRED",
                        401,
                        "qp_connection_error",
                        false,
                        "error.auth_required",
                        null);
        ErrorEnvelope envelope =
                new ErrorEnvelope(
                        "1.0",
                        "r-1",
                        "i-1",
                        ErrorObject.of(
                                entry,
                                "Missing or invalid bearer token",
                                Map.of("reason", "bearer_token_required")),
                        List.of());
        // Expected text written by hand from docs/06 "Antwortformat".
        assertEquals(
                "{\"protocol_version\":\"1.0\",\"request_id\":\"r-1\",\"instance_id\":\"i-1\","
                        + "\"error\":{\"code\":\"AUTH_REQUIRED\","
                        + "\"message_key\":\"error.auth_required\","
                        + "\"message\":\"Missing or invalid bearer token\","
                        + "\"details\":{\"reason\":\"bearer_token_required\"},"
                        + "\"retryable\":false},\"warnings\":[]}",
                ProtocolJson.toJson(envelope));
    }

    @Test
    void successEnvelopeWrapsData() {
        SuccessEnvelope<Map<String, Object>> envelope =
                new SuccessEnvelope<>(
                        "1.0",
                        "r",
                        "i",
                        Map.of("k", 1),
                        List.of(new ProtocolWarning("W", "warning.w", "w")));
        assertEquals(
                "{\"protocol_version\":\"1.0\",\"request_id\":\"r\",\"instance_id\":\"i\","
                        + "\"data\":{\"k\":1},\"warnings\":[{\"code\":\"W\","
                        + "\"message_key\":\"warning.w\","
                        + "\"message\":\"w\"}]}",
                ProtocolJson.toJson(envelope));
    }

    @Test
    void capabilityRulesAreEnforced() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Capability(
                                "bridge.health",
                                true,
                                "why",
                                null,
                                List.of(),
                                List.of(),
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Capability(
                                "bridge.health",
                                true,
                                null,
                                "code",
                                List.of(),
                                List.of(),
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Capability(
                                "objects.read",
                                false,
                                null,
                                "not_implemented",
                                List.of(),
                                List.of(),
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Capability(
                                "objects.read",
                                false,
                                " ",
                                "not_implemented",
                                List.of(),
                                List.of(),
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Capability(
                                "objects.read",
                                false,
                                "text",
                                "Not Implemented",
                                List.of(),
                                List.of(),
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new Capability(
                                "objects.read",
                                false,
                                "text",
                                "not_implemented",
                                List.of("live"),
                                List.of(),
                                Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Capability.available(
                                "objects.read", List.of("live", "live"), List.of(), Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        Capability.available(
                                "objects.read", List.of("desktop"), List.of(), Map.of()));
        assertThrows(
                IllegalArgumentException.class, () -> Capability.unavailable("Health", "x", "x"));
        assertThrows(
                IllegalArgumentException.class,
                () -> Capability.unavailable("objects..read", "x", "x"));
        assertEquals(
                "{\"id\":\"objects.read\",\"available\":false,\"reason\":\"Not implemented\","
                        + "\"reason_code\":\"not_implemented\",\"modes\":[],\"tested_versions\":[],"
                        + "\"limits\":{}}",
                ProtocolJson.toJson(
                        Capability.unavailable(
                                "objects.read", "Not implemented", "not_implemented")));
    }

    @Test
    void capabilityIdsAreUnique() {
        Capability a = Capability.unavailable("objects.read", "Not implemented", "not_implemented");
        assertThrows(
                IllegalArgumentException.class, () -> new CapabilitiesData("live", List.of(a, a)));
    }

    @Test
    void manifestRejectsInvalidPorts() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DiscoveryManifest(
                                "worker",
                                1,
                                null,
                                "i",
                                "127.0.0.1",
                                0,
                                "0.7.0",
                                "0.1.0",
                                "1.0",
                                "/t",
                                "2026-01-01T00:00:00Z"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DiscoveryManifest(
                                "worker",
                                1,
                                null,
                                "i",
                                "127.0.0.1",
                                65536,
                                "0.7.0",
                                "0.1.0",
                                "1.0",
                                "/t",
                                "2026-01-01T00:00:00Z"));
    }
}
