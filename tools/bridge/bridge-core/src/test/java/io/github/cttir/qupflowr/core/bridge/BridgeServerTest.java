/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.cttir.qupflowr.core.http.Limits;
import io.github.cttir.qupflowr.core.http.RawHttp;
import io.github.cttir.qupflowr.protocol.ErrorCodeRegistry;
import io.github.cttir.qupflowr.protocol.HostType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Protocol v1 behaviour of the bridge over raw HTTP (Host and Origin headers exactly as written).
 */
class BridgeServerTest {

    @TempDir Path tmp;

    private BridgeServer server;
    private BridgeClient client;

    @BeforeEach
    void startServer() throws IOException {
        server =
                BridgeServer.start(
                        new BridgeHostInfo(
                                tmp.resolve("runtime"),
                                HostType.LIVE,
                                "0.7.0",
                                "9.9.9-test",
                                () -> false));
        client = new BridgeClient(server);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void tokenIs256BitBase64UrlAndNotInManifest() throws IOException {
        String token = client.token();
        assertEquals(43, token.length());
        assertEquals(32, Base64.getUrlDecoder().decode(token).length);
        assertFalse(Files.readString(server.manifestPath()).contains(token));
    }

    @Test
    void discoveryFilesArePrivateAndComplete() throws IOException {
        Path runtime = tmp.resolve("runtime");
        Path instances = runtime.resolve("instances");
        assertEquals("rwx------", mode(runtime));
        assertEquals("rwx------", mode(instances));
        assertEquals("rw-------", mode(server.manifestPath()));
        assertEquals("rw-------", mode(server.tokenPath()));
        assertEquals(instances.resolve(server.instanceId() + ".json"), server.manifestPath());
        assertEquals(instances.resolve(server.instanceId() + ".token"), server.tokenPath());
        try (Stream<Path> files = Files.list(instances)) {
            assertEquals(2, files.count(), "no temporary files left");
        }
        JsonObject m =
                JsonParser.parseString(Files.readString(server.manifestPath())).getAsJsonObject();
        assertEquals(
                Set.of(
                        "host_type",
                        "pid",
                        "process_start_time",
                        "instance_id",
                        "host",
                        "port",
                        "qupath_version",
                        "bridge_version",
                        "protocol_version",
                        "token_file",
                        "created_at"),
                m.keySet());
        assertEquals("live", m.get("host_type").getAsString());
        assertEquals(ProcessHandle.current().pid(), m.get("pid").getAsLong());
        assertEquals(server.instanceId(), m.get("instance_id").getAsString());
        assertEquals("127.0.0.1", m.get("host").getAsString());
        assertEquals(server.port(), m.get("port").getAsInt());
        assertEquals("0.7.0", m.get("qupath_version").getAsString());
        assertEquals("9.9.9-test", m.get("bridge_version").getAsString());
        assertEquals("1.0", m.get("protocol_version").getAsString());
        assertEquals(server.tokenPath().toString(), m.get("token_file").getAsString());
        Instant created = Instant.parse(m.get("created_at").getAsString());
        assertTrue(Math.abs(Instant.now().toEpochMilli() - created.toEpochMilli()) < 60_000);
        Instant started = Instant.parse(m.get("process_start_time").getAsString());
        assertEquals(
                ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli(),
                started.toEpochMilli());
        assertTrue(Files.readString(server.manifestPath()).endsWith("}\n"));
    }

    @Test
    void healthReportsHostValues() throws IOException {
        String requestId = UUID.randomUUID().toString();
        Map<String, String> headers = client.authorized();
        headers.put("X-Request-Id", requestId);
        RawHttp.Result r = client.send("GET", "/v1/health", headers, null);
        assertEquals(200, r.status());
        assertEquals("application/json; charset=utf-8", r.header("Content-Type"));
        assertEquals("no-store", r.header("Cache-Control"));
        assertEquals(requestId, r.header("X-Request-Id"));
        JsonObject body = BridgeClient.json(r);
        assertEquals(
                Set.of("protocol_version", "request_id", "instance_id", "data", "warnings"),
                body.keySet());
        assertEquals("1.0", body.get("protocol_version").getAsString());
        assertEquals(requestId, body.get("request_id").getAsString());
        assertEquals(server.instanceId(), body.get("instance_id").getAsString());
        assertEquals(0, body.getAsJsonArray("warnings").size());
        JsonObject data = body.getAsJsonObject("data");
        assertEquals("ready", data.get("status").getAsString());
        assertEquals("live", data.get("host_type").getAsString());
        assertEquals("0.7.0", data.get("qupath_version").getAsString());
        assertEquals("9.9.9-test", data.get("bridge_version").getAsString());
        assertEquals("1.0", data.get("protocol_version").getAsString());
        assertEquals(Runtime.version().toString(), data.get("java_version").getAsString());
        assertFalse(data.get("fx_thread_blocked").getAsBoolean());
    }

    @Test
    void requestIdIsEchoedOnlyForCanonicalUuids() throws IOException {
        for (String bad :
                List.of(
                        "not-a-uuid",
                        "<script>",
                        "0-0-0-0-0",
                        UUID.randomUUID().toString().toUpperCase())) {
            Map<String, String> headers = client.authorized();
            headers.put("X-Request-Id", bad);
            RawHttp.Result r = client.send("GET", "/v1/health", headers, null);
            String id = BridgeClient.json(r).get("request_id").getAsString();
            assertNotEquals(bad, id);
            assertEquals(id, UUID.fromString(id).toString());
            assertFalse(r.raw().contains(bad));
        }
    }

    @Test
    void capabilitiesAreHonest() throws IOException {
        RawHttp.Result r = client.get("/v1/capabilities");
        assertEquals(200, r.status());
        JsonObject data = BridgeClient.json(r).getAsJsonObject("data");
        assertEquals("live", data.get("host_type").getAsString());
        JsonArray entries = data.getAsJsonArray("capabilities");
        Set<String> ids = new HashSet<>();
        List<String> available = new ArrayList<>();
        for (JsonElement element : entries) {
            JsonObject entry = element.getAsJsonObject();
            assertEquals(
                    Set.of(
                            "id",
                            "available",
                            "reason",
                            "reason_code",
                            "modes",
                            "tested_versions",
                            "limits"),
                    entry.keySet());
            assertTrue(ids.add(entry.get("id").getAsString()), "unique ids");
            if (entry.get("available").getAsBoolean()) {
                available.add(entry.get("id").getAsString());
                assertTrue(entry.get("reason").isJsonNull());
                assertTrue(entry.get("reason_code").isJsonNull());
                assertEquals("[\"live\",\"worker\"]", entry.get("modes").toString());
                assertEquals("[\"0.7.0\"]", entry.get("tested_versions").toString());
                JsonObject limits = entry.getAsJsonObject("limits");
                assertEquals(0, limits.get("max_request_body_bytes").getAsLong());
                assertEquals(64, limits.get("max_connections").getAsLong());
                assertEquals(8192, limits.get("max_request_line_bytes").getAsLong());
                assertEquals(16384, limits.get("max_header_block_bytes").getAsLong());
                assertEquals(100, limits.get("max_header_count").getAsLong());
                assertEquals(5000, limits.get("idle_timeout_ms").getAsLong());
                assertEquals(10000, limits.get("header_deadline_ms").getAsLong());
                assertEquals(30000, limits.get("request_deadline_ms").getAsLong());
            } else {
                assertEquals("not_implemented", entry.get("reason_code").getAsString());
                assertEquals(
                        "Not implemented in this bridge build", entry.get("reason").getAsString());
                assertEquals(0, entry.getAsJsonArray("modes").size());
                assertEquals(0, entry.getAsJsonArray("tested_versions").size());
            }
        }
        // Only what this build serves is available (independent list: the two routes of T01).
        assertEquals(List.of("bridge.health", "bridge.capabilities"), available);
        assertTrue(
                ids.containsAll(
                        List.of(
                                "objects.read",
                                "patches.write",
                                "viewer.select",
                                "pixels.region",
                                "jobs.run",
                                "measurements.dynamic")));
    }

    @Test
    void statusCodesFollowTheContract() throws IOException {
        String host = client.host();
        String bearer = "Bearer " + client.token();
        assertError(
                send("GET", "/v1/health", Map.of("Host", host)),
                401,
                "AUTH_REQUIRED",
                "bearer_token_required");
        assertError(
                send("GET", "/v1/health", Map.of("Host", host, "Authorization", "Bearer wrong")),
                401,
                "AUTH_REQUIRED",
                "bearer_token_required");
        assertError(
                send(
                        "GET",
                        "/v1/health",
                        Map.of("Host", host, "Authorization", "Basic " + client.token())),
                401,
                "AUTH_REQUIRED",
                "bearer_token_required");
        assertEquals(
                200,
                send(
                                "GET",
                                "/v1/health",
                                Map.of(
                                        "Host",
                                        "localhost:" + client.port(),
                                        "Authorization",
                                        bearer))
                        .status());
        assertError(
                send("GET", "/v1/health", Map.of("Host", "evil.example", "Authorization", bearer)),
                403,
                "SCOPE_DENIED",
                "host_not_allowed");
        assertError(
                send(
                        "GET",
                        "/v1/health",
                        Map.of(
                                "Host",
                                host,
                                "Authorization",
                                bearer,
                                "Origin",
                                "http://127.0.0.1:" + client.port())),
                403,
                "SCOPE_DENIED",
                "origin_not_allowed");
        assertError(
                send("GET", "/v1/health", Map.of("Host", host, "Origin", "null")),
                403,
                "SCOPE_DENIED",
                "origin_not_allowed");
        assertError(
                send("GET", "/v1/nope", Map.of("Host", host)),
                401,
                "AUTH_REQUIRED",
                "bearer_token_required");
        assertError(
                send("GET", "/v1/nope", Map.of("Host", host, "Authorization", bearer)),
                404,
                "ENDPOINT_NOT_FOUND",
                "unknown_endpoint");
        RawHttp.Result post =
                send("POST", "/v1/health", Map.of("Host", host, "Authorization", bearer));
        assertError(post, 405, "METHOD_NOT_ALLOWED", "method_not_allowed");
        assertEquals("GET", post.header("Allow"));
        RawHttp.Result head =
                send("HEAD", "/v1/capabilities", Map.of("Host", host, "Authorization", bearer));
        assertEquals(405, head.status());
        assertEquals("GET", head.header("Allow"));
        assertEquals("", head.body(), "HEAD responses carry no body");
        assertError(
                send("GET", "/v1/%2e%2e/health", Map.of("Host", host, "Authorization", bearer)),
                400,
                "MALFORMED_REQUEST",
                "encoded_separator_or_dot");
        assertEquals(
                "Bearer realm=\"qupflowr\"",
                send("GET", "/v1/health", Map.of("Host", host)).header("WWW-Authenticate"));
    }

    @Test
    void bodyLimitsFollowTheRouteAndPrecedence() throws IOException {
        String host = client.host();
        String bearer = "Bearer " + client.token();
        RawHttp.Result withBody =
                sendDeclared(
                        "GET", "/v1/health", Map.of("Host", host, "Authorization", bearer), 10);
        assertError(withBody, 413, "PAYLOAD_TOO_LARGE", "content_length_exceeds_limit");
        assertEquals(
                0,
                BridgeClient.json(withBody)
                        .getAsJsonObject("error")
                        .getAsJsonObject("details")
                        .get("limit_bytes")
                        .getAsLong());
        long overAbsolute = Limits.DEFAULT_MAX_BODY_BYTES + 1;
        assertError(
                sendDeclared("POST", "/v1/health", Map.of("Host", host), overAbsolute),
                401,
                "AUTH_REQUIRED",
                "bearer_token_required");
        assertError(
                sendDeclared(
                        "POST",
                        "/v1/nope",
                        Map.of("Host", host, "Authorization", bearer),
                        overAbsolute),
                404,
                "ENDPOINT_NOT_FOUND",
                "unknown_endpoint");
        assertError(
                sendDeclared(
                        "POST",
                        "/v1/health",
                        Map.of("Host", host, "Authorization", bearer),
                        overAbsolute),
                405,
                "METHOD_NOT_ALLOWED",
                "method_not_allowed");
    }

    @Test
    void errorEnvelopeUsesRegistryValues() throws IOException {
        ErrorCodeRegistry registry = ErrorCodeRegistry.bundled();
        RawHttp.Result r = send("GET", "/v1/health", Map.of("Host", client.host()));
        JsonObject body = BridgeClient.json(r);
        assertEquals(
                Set.of("protocol_version", "request_id", "instance_id", "error", "warnings"),
                body.keySet());
        JsonObject error = body.getAsJsonObject("error");
        assertEquals(
                Set.of("code", "message_key", "message", "details", "retryable"), error.keySet());
        assertEquals(
                registry.require("AUTH_REQUIRED").messageKey(),
                error.get("message_key").getAsString());
        assertEquals(
                registry.require("AUTH_REQUIRED").retryable(),
                error.get("retryable").getAsBoolean());
        assertEquals((int) registry.require("AUTH_REQUIRED").httpStatus(), r.status());
    }

    @Test
    void workerReportsNullFxStateAndOwnHostType() throws IOException {
        BridgeServer worker =
                BridgeServer.start(
                        new BridgeHostInfo(
                                tmp.resolve("runtime-worker"),
                                HostType.WORKER,
                                "0.7.0",
                                "9.9.9-test",
                                null));
        try {
            BridgeClient workerClient = new BridgeClient(worker);
            JsonObject data =
                    BridgeClient.json(workerClient.get("/v1/health")).getAsJsonObject("data");
            assertEquals("worker", data.get("host_type").getAsString());
            assertTrue(data.get("fx_thread_blocked").isJsonNull());
            JsonObject caps =
                    BridgeClient.json(workerClient.get("/v1/capabilities")).getAsJsonObject("data");
            assertEquals("worker", caps.get("host_type").getAsString());
            assertNotEquals(client.token(), workerClient.token());
        } finally {
            worker.stop();
        }
    }

    @Test
    void failingFxProbeReportsBlocked() throws IOException {
        BridgeServer live =
                BridgeServer.start(
                        new BridgeHostInfo(
                                tmp.resolve("runtime-fx"),
                                HostType.LIVE,
                                "0.7.0",
                                "t",
                                () -> {
                                    throw new IllegalStateException("toolkit gone");
                                }));
        try {
            JsonObject data =
                    BridgeClient.json(new BridgeClient(live).get("/v1/health"))
                            .getAsJsonObject("data");
            assertTrue(data.get("fx_thread_blocked").getAsBoolean());
        } finally {
            live.stop();
        }
    }

    @Test
    void stopRemovesDiscoveryFilesAndClosesThePort() throws Exception {
        Path instances = tmp.resolve("runtime").resolve("instances");
        int port = server.port();
        server.stop();
        server.stop();
        assertFalse(server.isRunning());
        try (Stream<Path> files = Files.list(instances)) {
            assertEquals(0, files.count());
        }
        assertThrows(IOException.class, () -> new java.net.Socket("127.0.0.1", port).close());
    }

    @Test
    void startFailureLeavesNothingBehind() throws IOException {
        Path notADirectory = tmp.resolve("file");
        Files.writeString(notADirectory, "x");
        assertThrows(
                IOException.class,
                () ->
                        BridgeServer.start(
                                new BridgeHostInfo(
                                        notADirectory, HostType.WORKER, "0.7.0", "t", null)));
        ErrorCodeRegistry incomplete =
                ErrorCodeRegistry.parse(
                        "[{\"code\":\"AUTH_REQUIRED\",\"http_status\":401,"
                                + "\"r_class\":\"qp_connection_error\",\"retryable\":false,"
                                + "\"message_key\":\"error.auth_required\"}]");
        Path runtime = tmp.resolve("runtime-incomplete");
        assertThrows(
                IllegalStateException.class,
                () ->
                        BridgeServer.start(
                                new BridgeHostInfo(runtime, HostType.WORKER, "0.7.0", "t", null),
                                Limits.defaults(),
                                incomplete));
        assertFalse(Files.exists(runtime), "registry is checked before any file is written");
    }

    private RawHttp.Result send(String method, String path, Map<String, String> headers)
            throws IOException {
        return client.send(method, path, new java.util.LinkedHashMap<>(headers), null);
    }

    private RawHttp.Result sendDeclared(
            String method, String path, Map<String, String> headers, long length)
            throws IOException {
        StringBuilder sb = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("Content-Length: ").append(length).append("\r\n\r\n");
        return RawHttp.exchange(
                client.port(),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                5000);
    }

    private static void assertError(RawHttp.Result r, int status, String code, String reason) {
        assertEquals(status, r.status(), r.raw());
        JsonObject error = BridgeClient.json(r).getAsJsonObject("error");
        assertEquals(code, error.get("code").getAsString());
        assertEquals(reason, error.getAsJsonObject("details").get("reason").getAsString());
        assertTrue(r.elapsedMillis() < 2000, "answered without waiting for a body");
    }

    private static String mode(Path path) throws IOException {
        return PosixFilePermissions.toString(
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS));
    }
}
