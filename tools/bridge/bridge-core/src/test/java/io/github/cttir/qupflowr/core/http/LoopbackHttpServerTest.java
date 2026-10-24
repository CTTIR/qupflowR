/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Transport rules of {@link LoopbackHttpServer} over raw sockets with short, test-specific limits.
 * Expected statuses follow RFC 9110/9112 (framing), docs/06 (Host, limits) and the SP01b transport
 * rules; they are written here, not derived from the implementation.
 */
class LoopbackHttpServerTest {

    private static final Limits LIMITS =
            Limits.defaults()
                    .withMaxConnections(4)
                    .withDeadlines(
                            Duration.ofMillis(300),
                            Duration.ofMillis(1000),
                            Duration.ofMillis(2000))
                    .withSizes(256, 512, 10, 1024)
                    .withLinger(4, 64, Duration.ofMillis(200));

    private LoopbackHttpServer server;
    private final AtomicInteger admitted = new AtomicInteger();
    private final AtomicInteger handled = new AtomicInteger();
    private final List<ServerEvent> events = new CopyOnWriteArrayList<>();
    private int port;

    /**
     * Test handler: /ok (GET), /echo (POST, sha256 of the streamed body, limit 1024), /small (limit
     * 4), /ignore (does not read its body), /slow-body (reads its body), /bad-header, /throw.
     * Header X-Reject gives 401; unknown paths 404. Errors are rendered as {@code
     * CODE/reason/limit}.
     */
    private final HttpHandler handler =
            new HttpHandler() {
                @Override
                public Admission admit(RequestHead head) {
                    admitted.incrementAndGet();
                    if (head.headers().contains("X-Reject")) {
                        return Admission.reject(text(401, "rejected"));
                    }
                    return switch (head.path()) {
                        case "/ok" -> Admission.accept(0, (h, body) -> ok("ok"));
                        case "/echo" ->
                                Admission.accept(
                                        1024,
                                        (h, body) -> {
                                            MessageDigest digest =
                                                    MessageDigest.getInstance("SHA-256");
                                            long total = 0;
                                            byte[] buffer = new byte[100];
                                            InputStream in = body.stream();
                                            int n;
                                            while ((n = in.read(buffer)) >= 0) {
                                                digest.update(buffer, 0, n);
                                                total += n;
                                            }
                                            return ok(
                                                    "bytes="
                                                            + total
                                                            + " sha256="
                                                            + HexFormat.of()
                                                                    .formatHex(digest.digest()));
                                        });
                        case "/small" ->
                                Admission.accept(
                                        4, (h, body) -> ok("small=" + body.readAllBytes(4).length));
                        case "/ignore" -> Admission.accept(1024, (h, body) -> ok("ignored"));
                        case "/bad-header" ->
                                Admission.accept(
                                        0,
                                        (h, body) ->
                                                new Response(
                                                        200,
                                                        Map.of("X-Evil", "a\r\nSet-Cookie: x=y"),
                                                        new byte[0]));
                        case "/throw" ->
                                Admission.accept(
                                        0,
                                        (h, body) -> {
                                            throw new IllegalStateException("boom");
                                        });
                        default -> Admission.reject(text(404, "not found"));
                    };
                }

                @Override
                public Response transportError(RequestHead head, TransportError error) {
                    return text(
                            error.status(),
                            error.code() + "/" + error.reason() + "/" + error.limitBytes());
                }
            };

    private Response ok(String body) {
        handled.incrementAndGet();
        return text(200, body);
    }

    private static Response text(int status, String body) {
        return new Response(
                status,
                Map.of("Content-Type", "text/plain; charset=utf-8"),
                body.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void start() throws IOException {
        restart(LIMITS);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private void restart(Limits limits) throws IOException {
        if (server != null) {
            server.close();
        }
        server = LoopbackHttpServer.start(handler, events::add, limits, HostPolicy.loopback());
        port = server.port();
    }

    private String host() {
        return "Host: 127.0.0.1:" + port + "\r\n";
    }

    private record Case(String name, String request, int status, String bodyContains) {}

    @TestFactory
    Stream<DynamicTest> framingAndPolicyStatusCodes() {
        String h = host();
        String longTarget = "/" + "a".repeat(300);
        List<Case> cases =
                List.of(
                        new Case("valid HTTP/1.1", "GET /ok HTTP/1.1\r\n" + h + "\r\n", 200, "ok"),
                        new Case("valid HTTP/1.0", "GET /ok HTTP/1.0\r\n" + h + "\r\n", 200, "ok"),
                        new Case(
                                "header names case-insensitive",
                                "GET /ok HTTP/1.1\r\nhOsT: 127.0.0.1:" + port + "\r\n\r\n",
                                200,
                                "ok"),
                        new Case(
                                "localhost authority",
                                "GET /ok HTTP/1.1\r\nHost: LOCALHOST:" + port + "\r\n\r\n",
                                200,
                                "ok"),
                        new Case(
                                "query may contain percent-encoding",
                                "GET /ok?x=%2e%2e HTTP/1.1\r\n" + h + "\r\n",
                                200,
                                "ok"),
                        new Case(
                                "garbage request line",
                                "HELLO\r\n\r\n",
                                400,
                                "invalid_request_line"),
                        new Case(
                                "empty request line",
                                "\r\n" + h + "\r\n",
                                400,
                                "invalid_request_line"),
                        new Case(
                                "HTTP/2.0",
                                "GET /ok HTTP/2.0\r\n" + h + "\r\n",
                                505,
                                "http_version_not_supported"),
                        new Case(
                                "bare LF",
                                "GET /ok HTTP/1.1\n" + h.replace("\r\n", "\n") + "\n",
                                400,
                                "bare_lf"),
                        new Case("bare CR", "GET /ok HTTP/1.1\r" + h + "\r\n", 400, "bare_cr"),
                        new Case(
                                "request line too long",
                                "GET " + longTarget + " HTTP/1.1\r\n" + h + "\r\n",
                                414,
                                "request_line_too_long"),
                        new Case(
                                "header block too large",
                                "GET /ok HTTP/1.1\r\n"
                                        + h
                                        + "X-Big: "
                                        + "b".repeat(600)
                                        + "\r\n\r\n",
                                431,
                                "header_block_too_large"),
                        new Case(
                                "too many headers",
                                "GET /ok HTTP/1.1\r\n" + h + "X-A: 1\r\n".repeat(10) + "\r\n",
                                431,
                                "too_many_headers"),
                        new Case(
                                "obs-fold",
                                "GET /ok HTTP/1.1\r\n" + h + "X-A: 1\r\n  continued\r\n\r\n",
                                400,
                                "obsolete_line_folding"),
                        new Case(
                                "duplicate Content-Length",
                                "POST /echo HTTP/1.1\r\n"
                                        + h
                                        + "Content-Length: 1\r\nContent-Length: 1\r\n\r\nx",
                                400,
                                "duplicate_content_length"),
                        new Case(
                                "Transfer-Encoding",
                                "POST /echo HTTP/1.1\r\n"
                                        + h
                                        + "Transfer-Encoding: chunked\r\n\r\n1\r\nx\r\n0\r\n\r\n",
                                411,
                                "transfer_encoding_not_supported"),
                        new Case(
                                "duplicate Host",
                                "GET /ok HTTP/1.1\r\n" + h + h + "\r\n",
                                400,
                                "duplicate_host"),
                        new Case(
                                "missing Host HTTP/1.1",
                                "GET /ok HTTP/1.1\r\n\r\n",
                                400,
                                "missing_host"),
                        new Case(
                                "missing Host HTTP/1.0",
                                "GET /ok HTTP/1.0\r\n\r\n",
                                403,
                                "host_not_allowed"),
                        new Case(
                                "foreign Host",
                                "GET /ok HTTP/1.1\r\nHost: evil.example\r\n\r\n",
                                403,
                                "host_not_allowed"),
                        new Case(
                                "encoded dots",
                                "GET /%2e%2e/ok HTTP/1.1\r\n" + h + "\r\n",
                                400,
                                "encoded_separator_or_dot"),
                        new Case(
                                "trailing slash",
                                "GET /ok/ HTTP/1.1\r\n" + h + "\r\n",
                                400,
                                "empty_path_segment"),
                        new Case(
                                "unsupported expectation",
                                "GET /ok HTTP/1.1\r\n" + h + "Expect: something\r\n\r\n",
                                417,
                                "unsupported_expectation"),
                        new Case(
                                "unknown path from handler",
                                "GET /nope HTTP/1.1\r\n" + h + "\r\n",
                                404,
                                "not found"),
                        // precedence: framing before Host before target before Expect before the
                        // handler
                        new Case(
                                "framing before Host",
                                "POST /ok HTTP/1.1\r\nHost: evil.example\r\n"
                                        + "Transfer-Encoding: chunked\r\n\r\n",
                                411,
                                "transfer_encoding_not_supported"),
                        new Case(
                                "Host before target",
                                "GET /%2e%2e HTTP/1.1\r\nHost: evil.example\r\n\r\n",
                                403,
                                "host_not_allowed"),
                        new Case(
                                "target before Expect",
                                "GET /x/../ok HTTP/1.1\r\n" + h + "Expect: nope\r\n\r\n",
                                400,
                                "dot_segment"),
                        new Case(
                                "Expect before handler",
                                "GET /ok HTTP/1.1\r\n" + h + "X-Reject: 1\r\nExpect: nope\r\n\r\n",
                                417,
                                "unsupported_expectation"),
                        new Case(
                                "handler rejection before size",
                                "POST /echo HTTP/1.1\r\n"
                                        + h
                                        + "X-Reject: 1\r\nContent-Length: 999999\r\n\r\n",
                                401,
                                "rejected"),
                        new Case(
                                "unknown route before size",
                                "POST /nope HTTP/1.1\r\n" + h + "Content-Length: 999999\r\n\r\n",
                                404,
                                "not found"),
                        new Case(
                                "route limit",
                                "POST /small HTTP/1.1\r\n" + h + "Content-Length: 5\r\n\r\nabcde",
                                413,
                                "PAYLOAD_TOO_LARGE/content_length_exceeds_limit/4"),
                        new Case(
                                "route without body",
                                "GET /ok HTTP/1.1\r\n" + h + "Content-Length: 1\r\n\r\nx",
                                413,
                                "PAYLOAD_TOO_LARGE/content_length_exceeds_limit/0"),
                        new Case(
                                "absolute limit caps the route",
                                "POST /echo HTTP/1.1\r\n" + h + "Content-Length: 1025\r\n\r\n",
                                413,
                                "PAYLOAD_TOO_LARGE/content_length_exceeds_limit/1024"));
        return cases.stream()
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        c.name(),
                                        () -> {
                                            RawHttp.Result r = RawHttp.exchange(port, c.request());
                                            assertEquals(
                                                    c.status(),
                                                    r.status(),
                                                    c.name() + ": " + r.raw());
                                            assertTrue(
                                                    r.body().contains(c.bodyContains()),
                                                    c.name() + ": " + r.body());
                                            assertEquals(
                                                    "close",
                                                    r.header("Connection"),
                                                    "Connection: close");
                                            assertEquals(
                                                    String.valueOf(
                                                            r.body()
                                                                    .getBytes(
                                                                            StandardCharsets.UTF_8)
                                                                    .length),
                                                    r.header("Content-Length"),
                                                    "Content-Length framing");
                                            assertEquals(null, r.header("Transfer-Encoding"));
                                        }));
    }

    @Test
    void rejectionHappensBeforeBodyAndWithout100Continue() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            long start = System.nanoTime();
            socket.getOutputStream()
                    .write(
                            ("POST /echo HTTP/1.1\r\n"
                                            + host()
                                            + "X-Reject: 1\r\n"
                                            + "Expect: 100-continue\r\n"
                                            + "Content-Length: 1000\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
            RawHttp.Result r = RawHttp.readResponse(socket, start);
            assertEquals(401, r.status());
            assertFalse(r.raw().contains("100 Continue"), r.raw());
            assertEquals(0, handled.get());
        }
    }

    @Test
    void continueIsSentOnlyAfterAdmissionThenBodyIsStreamed() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            OutputStream out = socket.getOutputStream();
            out.write(
                    ("POST /echo HTTP/1.1\r\n"
                                    + host()
                                    + "Expect: 100-continue\r\nContent-Length: 5\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            InputStream in = socket.getInputStream();
            byte[] interim = in.readNBytes("HTTP/1.1 100 Continue\r\n\r\n".length());
            assertEquals(
                    "HTTP/1.1 100 Continue\r\n\r\n",
                    new String(interim, StandardCharsets.US_ASCII));
            out.write("hello".getBytes(StandardCharsets.US_ASCII));
            RawHttp.Result r = RawHttp.readResponse(socket, System.nanoTime());
            assertEquals(200, r.status());
            // sha256("hello") computed independently (coreutils sha256sum)
            assertEquals(
                    "bytes=5 sha256=2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e"
                            + "73043362938b9824",
                    r.body());
        }
    }

    @Test
    void bodyAtRouteLimitIsStreamedExactly() throws Exception {
        byte[] head =
                ("POST /echo HTTP/1.1\r\n" + host() + "Content-Length: 1024\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] request = new byte[head.length + 1024 + 7];
        System.arraycopy(head, 0, request, 0, head.length);
        for (int i = 0; i < 1024; i++) {
            request[head.length + i] = (byte) i;
        }
        // 7 extra bytes after the declared body are never processed
        System.arraycopy(
                "GET /ok".getBytes(StandardCharsets.US_ASCII), 0, request, head.length + 1024, 7);
        RawHttp.Result r = RawHttp.exchange(port, request, 3000);
        assertEquals(200, r.status());
        byte[] body = new byte[1024];
        for (int i = 0; i < 1024; i++) {
            body[i] = (byte) i;
        }
        String expected =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        assertEquals("bytes=1024 sha256=" + expected, r.body());
        assertEquals(1, handled.get());
    }

    @Test
    void oversizedBodyIsRejectedImmediately() throws IOException {
        RawHttp.Result r =
                RawHttp.exchange(
                        port,
                        ("POST /echo HTTP/1.1\r\n" + host() + "Content-Length: 1025\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII),
                        3000);
        assertEquals(413, r.status());
        assertTrue(
                r.elapsedMillis() < 250,
                "answered without waiting for the body: " + r.elapsedMillis());
        RawHttp.Result huge =
                RawHttp.exchange(
                        port,
                        ("POST /echo HTTP/1.1\r\n"
                                        + host()
                                        + "Content-Length: 99999999999999999999999\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII),
                        3000);
        assertEquals(413, huge.status());
        assertEquals(0, handled.get());
    }

    @Test
    void handlerMayIgnoreItsBody() throws IOException {
        RawHttp.Result r =
                RawHttp.exchange(
                        port,
                        "POST /ignore HTTP/1.1\r\n"
                                + host()
                                + "Content-Length: 1000\r\n\r\n"
                                + "z".repeat(1000));
        assertEquals(200, r.status());
        assertEquals("ignored", r.body());
    }

    @Test
    void idleHeaderReadTimesOut() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            long start = System.nanoTime();
            socket.getOutputStream()
                    .write(
                            ("GET /ok HTTP/1.1\r\n" + host() + "X-Unfinished: 1")
                                    .getBytes(StandardCharsets.US_ASCII));
            RawHttp.Result r = RawHttp.readResponse(socket, start);
            assertEquals(408, r.status(), r.raw());
            assertTrue(r.body().contains("REQUEST_TIMEOUT/idle_timeout"));
            assertTrue(
                    r.elapsedMillis() >= 250 && r.elapsedMillis() < 1500,
                    "elapsed " + r.elapsedMillis());
            assertEquals(1, server.stats().headerTimeouts());
        }
    }

    @Test
    void trickledHeadersAreCutAtHeaderDeadline() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            long start = System.nanoTime();
            OutputStream out = socket.getOutputStream();
            out.write(
                    ("GET /ok HTTP/1.1\r\n" + host() + "X-Slow: ")
                            .getBytes(StandardCharsets.US_ASCII));
            Thread writer = Thread.ofVirtual().start(() -> trickle(out, 40, 'a'));
            RawHttp.Result r = RawHttp.readResponse(socket, start);
            writer.join();
            assertTrue(r.status() == 408 || r.closedWithoutResponse(), r.raw());
            assertTrue(
                    r.elapsedMillis() >= 900 && r.elapsedMillis() < 1800,
                    "elapsed " + r.elapsedMillis());
        }
    }

    @Test
    void trickledBodyIsCutAtRequestDeadline() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(6000);
            long start = System.nanoTime();
            OutputStream out = socket.getOutputStream();
            out.write(
                    ("POST /echo HTTP/1.1\r\n" + host() + "Content-Length: 1000\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
            Thread writer = Thread.ofVirtual().start(() -> trickle(out, 60, 'b'));
            RawHttp.Result r = RawHttp.readResponse(socket, start);
            writer.join();
            assertTrue(r.status() == 408 || r.closedWithoutResponse(), r.raw());
            assertTrue(
                    r.elapsedMillis() >= 1900 && r.elapsedMillis() < 3000,
                    "elapsed " + r.elapsedMillis());
            assertEquals(0, handled.get());
        }
    }

    @Test
    void headerPhaseConnectionsAreEvictedOldestFirst() throws Exception {
        List<Socket> idle = new ArrayList<>();
        try {
            for (int i = 0; i < LIMITS.maxConnections(); i++) {
                Socket s = new Socket("127.0.0.1", port);
                s.setSoTimeout(3000);
                s.getOutputStream()
                        .write(
                                ("GET /ok HTTP/1.1\r\n" + host())
                                        .getBytes(StandardCharsets.US_ASCII));
                idle.add(s);
                Thread.sleep(20); // distinct accept order
            }
            waitFor(() -> server.stats().headerPhase() == LIMITS.maxConnections());
            RawHttp.Result ok =
                    RawHttp.exchange(
                            port,
                            ("GET /ok HTTP/1.1\r\n" + host() + "\r\n")
                                    .getBytes(StandardCharsets.US_ASCII),
                            3000);
            assertEquals(200, ok.status(), ok.raw());
            assertTrue(ok.elapsedMillis() < 250, "served immediately: " + ok.elapsedMillis());
            RawHttp.Result evicted = RawHttp.readResponse(idle.get(0), System.nanoTime());
            assertEquals(503, evicted.status(), evicted.raw());
            assertEquals("HOST_BUSY/evicted_header_phase/null", evicted.body());
            assertEquals("1", evicted.header("Retry-After"));
            assertEquals(1, server.stats().evicted());
            assertEquals(0, server.stats().busyRejected());
        } finally {
            for (Socket s : idle) {
                s.close();
            }
        }
    }

    @Test
    void silentConnectionsBecomeEvictableAfterTheGracePeriod() throws Exception {
        restart(
                LIMITS.withDeadlines(
                                Duration.ofSeconds(5),
                                Duration.ofSeconds(10),
                                Duration.ofSeconds(30))
                        .withEvictionGrace(Duration.ofMillis(400)));
        List<Socket> silent = new ArrayList<>();
        try {
            for (int i = 0; i < LIMITS.maxConnections(); i++) {
                Socket s = new Socket("127.0.0.1", port);
                s.setSoTimeout(3000);
                silent.add(s);
            }
            waitFor(() -> server.stats().headerPhase() == LIMITS.maxConnections());
            // Within the grace period a silent connection may still be about to send its request.
            RawHttp.Result early =
                    RawHttp.exchange(
                            port,
                            ("GET /ok HTTP/1.1\r\n" + host() + "\r\n")
                                    .getBytes(StandardCharsets.US_ASCII),
                            3000);
            assertEquals(503, early.status(), early.raw());
            assertEquals("HOST_BUSY/connection_limit/null", early.body());
            assertEquals(0, server.stats().evicted());
            Thread.sleep(500);
            RawHttp.Result late =
                    RawHttp.exchange(
                            port,
                            ("GET /ok HTTP/1.1\r\n" + host() + "\r\n")
                                    .getBytes(StandardCharsets.US_ASCII),
                            3000);
            assertEquals(200, late.status(), late.raw());
            assertEquals(1, server.stats().evicted());
            RawHttp.Result evicted = RawHttp.readResponse(silent.get(0), System.nanoTime());
            assertEquals("HOST_BUSY/evicted_header_phase/null", evicted.body());
        } finally {
            for (Socket s : silent) {
                s.close();
            }
        }
    }

    @Test
    void busyOnlyWhenEveryCountedConnectionIsAdmitted() throws Exception {
        List<Socket> uploads = new ArrayList<>();
        try {
            for (int i = 0; i < LIMITS.maxConnections(); i++) {
                Socket s = new Socket("127.0.0.1", port);
                s.getOutputStream()
                        .write(
                                ("POST /echo HTTP/1.1\r\n"
                                                + host()
                                                + "Content-Length: 1000\r\n\r\nx")
                                        .getBytes(StandardCharsets.US_ASCII));
                uploads.add(s);
            }
            waitFor(
                    () ->
                            server.stats().active() == LIMITS.maxConnections()
                                    && server.stats().headerPhase() == 0);
            RawHttp.Result busy =
                    RawHttp.exchange(
                            port,
                            ("GET /ok HTTP/1.1\r\n" + host() + "\r\n")
                                    .getBytes(StandardCharsets.US_ASCII),
                            3000);
            assertEquals(503, busy.status(), busy.raw());
            assertEquals("HOST_BUSY/connection_limit/null", busy.body());
            assertEquals("1", busy.header("Retry-After"));
            assertTrue(
                    busy.elapsedMillis() < 250,
                    "busy answer is immediate: " + busy.elapsedMillis());
            assertEquals(0, server.stats().evicted());
        } finally {
            for (Socket s : uploads) {
                s.close();
            }
        }
        waitFor(() -> server.stats().active() == 0);
        assertEquals(
                200, RawHttp.exchange(port, "GET /ok HTTP/1.1\r\n" + host() + "\r\n").status());
    }

    @Test
    void slotIsReleasedBeforeLinger() throws Exception {
        restart(LIMITS.withMaxConnections(1).withLinger(4, 64, Duration.ofSeconds(2)));
        try (Socket slowCloser = new Socket("127.0.0.1", port)) {
            slowCloser.setSoTimeout(3000);
            slowCloser
                    .getOutputStream()
                    .write(
                            ("GET /ok HTTP/1.1\r\n" + host() + "\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
            String first = RawHttp.readOneResponse(slowCloser);
            assertTrue(first.startsWith("HTTP/1.1 200 "), first);
            // The first client keeps its socket open, so the server lingers for up to 2 s.
            waitFor(() -> server.stats().active() == 0);
            assertEquals(1, server.stats().open());
            RawHttp.Result second =
                    RawHttp.exchange(
                            port,
                            ("GET /ok HTTP/1.1\r\n" + host() + "\r\n")
                                    .getBytes(StandardCharsets.US_ASCII),
                            3000);
            assertEquals(200, second.status(), second.raw());
            assertTrue(second.elapsedMillis() < 500, "not blocked by the lingering connection");
            assertEquals(0, server.stats().busyRejected());
        }
    }

    @Test
    void lingerIsSkippedWhenNoSlotIsFree() throws Exception {
        restart(LIMITS.withLinger(4, 0, Duration.ofMillis(200)));
        RawHttp.Result r = RawHttp.exchange(port, "GET /ok HTTP/1.1\r\n" + host() + "\r\n");
        assertEquals(200, r.status());
        waitFor(() -> server.stats().lingerSkipped() == 1);
    }

    @Test
    void halfOpenConnectionsDoNotDelayOthers() throws Exception {
        restart(Limits.defaults().withMaxConnections(16).withSizes(256, 512, 10, 1024));
        List<Socket> stalled = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                Socket s = new Socket("127.0.0.1", port);
                s.getOutputStream()
                        .write(
                                ("GET /ok HTTP/1.1\r\n" + host())
                                        .getBytes(StandardCharsets.US_ASCII));
                stalled.add(s);
            }
            for (int i = 0; i < 30; i++) {
                RawHttp.Result r = RawHttp.exchange(port, "GET /ok HTTP/1.1\r\n" + host() + "\r\n");
                assertEquals(200, r.status());
                assertTrue(r.elapsedMillis() < 500, "elapsed " + r.elapsedMillis());
            }
        } finally {
            for (Socket s : stalled) {
                s.close();
            }
        }
    }

    @RepeatedTest(10)
    void concurrentRequestsGetOkOrBusyNeverSilence() throws Exception {
        restart(LIMITS.withLinger(64, 64, Duration.ofMillis(200)));
        int clients = 40;
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            List<Future<RawHttp.Result>> futures = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                futures.add(
                        pool.submit(
                                () ->
                                        RawHttp.exchange(
                                                port,
                                                ("GET /ok HTTP/1.1\r\n" + host() + "\r\n")
                                                        .getBytes(StandardCharsets.US_ASCII),
                                                5000)));
            }
            int ok = 0;
            for (Future<RawHttp.Result> f : futures) {
                RawHttp.Result r = f.get();
                assertTrue(
                        r.status() == 200 || r.status() == 503,
                        "status " + r.status() + " raw " + r.raw());
                if (r.status() == 200) {
                    ok++;
                }
            }
            assertTrue(ok > 0);
        }
    }

    @Test
    void handlerFailuresBecome500WithoutDetails() throws IOException {
        RawHttp.Result thrown = RawHttp.exchange(port, "GET /throw HTTP/1.1\r\n" + host() + "\r\n");
        assertEquals(500, thrown.status());
        assertEquals("INTERNAL_ERROR/handler_failed/null", thrown.body());
        assertFalse(thrown.raw().contains("boom"));
        RawHttp.Result injected =
                RawHttp.exchange(port, "GET /bad-header HTTP/1.1\r\n" + host() + "\r\n");
        assertEquals(500, injected.status());
        assertFalse(injected.raw().contains("Set-Cookie"));
        assertEquals(2, server.stats().handlerFailed());
    }

    @Test
    void pipelinedRequestsGetExactlyOneResponse() throws IOException {
        RawHttp.Result r =
                RawHttp.exchange(
                        port,
                        "GET /ok HTTP/1.1\r\n"
                                + host()
                                + "\r\n"
                                + "GET /ok HTTP/1.1\r\n"
                                + host()
                                + "\r\n");
        assertEquals(200, r.status());
        assertEquals(1, r.raw().split("HTTP/1.1 ", -1).length - 1, r.raw());
        assertEquals(1, handled.get());
    }

    @Test
    void listensOnIpv4LoopbackOnly() throws IOException {
        InetAddress loopback6 = InetAddress.getByName("::1");
        assertThrows(
                IOException.class,
                () -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(loopback6, port), 1000);
                    }
                });
        assertEquals(
                200, RawHttp.exchange(port, "GET /ok HTTP/1.1\r\n" + host() + "\r\n").status());
    }

    @Test
    void closeStopsListenerAndOpenConnections() throws Exception {
        Socket idle = new Socket("127.0.0.1", port);
        idle.setSoTimeout(2000);
        waitFor(() -> server.stats().open() == 1);
        long start = System.nanoTime();
        server.close();
        int read;
        try {
            read = idle.getInputStream().read();
        } catch (IOException e) {
            read = -1;
        }
        assertEquals(-1, read);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1500);
        idle.close();
        assertThrows(IOException.class, () -> new Socket("127.0.0.1", port).close());
    }

    @Test
    void eventsReportOutcomesWithoutHeaderValues() throws IOException {
        RawHttp.Result r =
                RawHttp.exchange(
                        port,
                        "GET /ok?secret=s3cr3t HTTP/1.1\r\n" + host() + "X-Secret: s3cr3t\r\n\r\n");
        assertEquals(200, r.status());
        ServerEvent event =
                events.stream()
                        .filter(e -> e.type() == ServerEvent.Type.RESPONSE)
                        .findFirst()
                        .orElseThrow();
        assertEquals(200, event.status());
        assertEquals("GET", event.method());
        assertEquals("/ok", event.path());
        assertFalse(event.toString().contains("s3cr3t"));
    }

    private static void trickle(OutputStream out, int count, char c) {
        try {
            for (int i = 0; i < count; i++) {
                Thread.sleep(100);
                out.write(c);
            }
        } catch (IOException | InterruptedException e) {
            // closed by the server
        }
    }

    static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not reached within 5 s");
            }
            Thread.sleep(10);
        }
    }
}
