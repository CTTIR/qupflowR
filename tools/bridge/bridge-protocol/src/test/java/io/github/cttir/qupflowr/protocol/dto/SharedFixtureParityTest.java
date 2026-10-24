/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.cttir.qupflowr.protocol.ErrorCodeRegistry;
import io.github.cttir.qupflowr.protocol.json.ProtocolJson;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Java DTOs, filled with the values of the shared valid fixtures written for the R package
 * (inst/schema/protocol/v1/fixtures/valid), serialise to the same JSON documents. The fixtures are
 * an independent oracle maintained with the schemas.
 */
class SharedFixtureParityTest {

    private static JsonElement fixture(String name) throws IOException {
        Path dir =
                Path.of(
                        System.getProperty("qupflowr.test.schemaDir"),
                        "protocol",
                        "v1",
                        "fixtures",
                        "valid");
        return ProtocolJson.parse(Files.readString(dir.resolve(name)));
    }

    @Test
    void healthLiveAndWorker() throws IOException {
        assertEquals(
                fixture("health--live.json"),
                ProtocolJson.toTree(
                        new HealthData(
                                "ready",
                                "live",
                                "0.7.0",
                                "0.1.0-SNAPSHOT",
                                "1.0",
                                "25.0.2+10",
                                false)));
        assertEquals(
                fixture("health--worker.json"),
                ProtocolJson.toTree(
                        new HealthData(
                                "ready",
                                "worker",
                                "0.7.0",
                                "0.1.0-SNAPSHOT",
                                "1.0",
                                "25.0.4.1+1-LTS",
                                null)));
    }

    @Test
    void discoveryManifest() throws IOException {
        assertEquals(
                fixture("discovery-manifest--worker.json"),
                ProtocolJson.toTree(
                        new DiscoveryManifest(
                                "worker",
                                2658485,
                                "2026-09-14T20:43:59.120Z",
                                "5b1f8a2c-3d4e-4f60-8a9b-0c1d2e3f4a5b",
                                "127.0.0.1",
                                40123,
                                "0.7.0",
                                "0.1.0-SNAPSHOT",
                                "1.0",
                                "/run/user/1000/qupflowr/instances/"
                                        + "5b1f8a2c-3d4e-4f60-8a9b-0c1d2e3f4a5b.token",
                                "2026-09-14T20:44:00.004Z")));
    }

    @Test
    void hostBusyErrorEnvelopeUsesRegistryValues() throws IOException {
        ErrorObject error =
                ErrorObject.of(
                        ErrorCodeRegistry.bundled().require("HOST_BUSY"),
                        "Too many open connections; retry later",
                        Map.of("reason", "connection_limit"));
        assertEquals(
                fixture("envelope-error--host-busy.json"),
                ProtocolJson.toTree(
                        new ErrorEnvelope(
                                "1.0",
                                "0d9a3c5e-8f7b-4a21-9c3d-2e1f0a9b8c7d",
                                "5b1f8a2c-3d4e-4f60-8a9b-0c1d2e3f4a5b",
                                error,
                                List.of())));
    }

    @Test
    void capabilities() throws IOException {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("max_page_size", 10000L);
        limits.put("max_body_bytes", 16777216L);
        limits.put("max_export_bytes", 5000000000L);
        JsonObject expected = fixture("capabilities--mixed.json").getAsJsonObject();
        JsonObject actual =
                ProtocolJson.toTree(
                                new CapabilitiesData(
                                        "live",
                                        List.of(
                                                Capability.available(
                                                        "objects.query",
                                                        List.of("live", "worker"),
                                                        List.of("0.7.0"),
                                                        limits),
                                                Capability.unavailable(
                                                        "measurements.dynamic.pixel_classifier",
                                                        "Live pixel classifier measurements are not"
                                                                + " supported yet",
                                                        "not_implemented"))))
                        .getAsJsonObject();
        // Two documented, schema-valid additions of this bridge: host_type, and reason_code written
        // as an
        // explicit null for available entries (the fixture omits the optional member).
        assertEquals("live", actual.remove("host_type").getAsString());
        JsonObject available = actual.getAsJsonArray("capabilities").get(0).getAsJsonObject();
        assertEquals(com.google.gson.JsonNull.INSTANCE, available.remove("reason_code"));
        assertEquals(expected, actual);
    }
}
