/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.cttir.qupflowr.core.http.Limits;
import io.github.cttir.qupflowr.core.http.RawHttp;
import io.github.cttir.qupflowr.core.http.ServerStats;
import io.github.cttir.qupflowr.protocol.HostType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mitigation of the unauthenticated header-phase denial of service found in the T00 SP01b
 * verification: with the default limits (cap 64, header deadline 10 s) a local process holding 64
 * or more connections in the header phase must not stop authenticated requests. Before the
 * mitigation such requests received {@code 503 HOST_BUSY} for up to 10 s.
 */
class HeaderPhaseDosTest {

    private static final int ATTACKERS = 100;

    @TempDir Path tmp;

    private BridgeServer server;
    private BridgeClient client;
    private final List<Socket> attackers = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server =
                BridgeServer.start(
                        new BridgeHostInfo(
                                tmp.resolve("runtime"), HostType.WORKER, "0.7.0", "t", null));
        client = new BridgeClient(server);
        assertEquals(64, Limits.defaults().maxConnections(), "default cap from SP01b");
    }

    @AfterEach
    void stop() throws IOException {
        for (Socket s : attackers) {
            s.close();
        }
        server.stop();
    }

    @Test
    void heldHeaderPhaseConnectionsDoNotBlockAuthenticatedHealth() throws Exception {
        for (int i = 0; i < ATTACKERS; i++) {
            Socket s = new Socket("127.0.0.1", client.port());
            // An unauthenticated request whose header block never ends.
            s.getOutputStream()
                    .write(
                            ("GET /v1/health HTTP/1.1\r\nHost: " + client.host() + "\r\nX-Stall: ")
                                    .getBytes(StandardCharsets.US_ASCII));
            attackers.add(s);
        }
        waitFor(() -> server.stats().accepted() >= ATTACKERS && server.stats().headerPhase() == 64);
        ServerStats before = server.stats();
        assertEquals(64, before.active(), "the cap is fully occupied by header-phase connections");
        assertEquals(
                ATTACKERS - 64,
                before.evicted(),
                "older attackers were evicted, none rejected busy");
        for (int i = 0; i < 30; i++) {
            RawHttp.Result r = client.get("/v1/health");
            assertEquals(200, r.status(), "request " + i + ": " + r.raw());
            assertTrue(
                    r.elapsedMillis() < 1000,
                    "request " + i + " took " + r.elapsedMillis() + " ms");
        }
        ServerStats after = server.stats();
        assertEquals(0, after.busyRejected());
        // Only the first request had to evict an attacker; its released slot served the others.
        assertEquals(ATTACKERS - 64 + 1, after.evicted());
        assertEquals(63, after.headerPhase());
    }

    @Test
    void silentConnectionsDoNotBlockAuthenticatedHealthAfterTheGracePeriod() throws Exception {
        for (int i = 0; i < ATTACKERS; i++) {
            attackers.add(new Socket("127.0.0.1", client.port()));
        }
        waitFor(() -> server.stats().accepted() >= ATTACKERS);
        Thread.sleep(Limits.defaults().evictionGrace().toMillis() + 250);
        for (int i = 0; i < 30; i++) {
            RawHttp.Result r = client.get("/v1/health");
            assertEquals(200, r.status(), "request " + i + ": " + r.raw());
            assertTrue(
                    r.elapsedMillis() < 1000,
                    "request " + i + " took " + r.elapsedMillis() + " ms");
        }
        assertTrue(server.stats().evicted() >= 1);
    }

    @Test
    void reconnectingAttackersDoNotBlockAuthenticatedHealth() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicInteger opened = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            threads.add(Thread.ofPlatform().daemon().start(() -> attack(running, opened)));
        }
        try {
            waitFor(() -> opened.get() > 200);
            for (int i = 0; i < 30; i++) {
                RawHttp.Result r = client.get("/v1/health");
                assertEquals(200, r.status(), "request " + i + ": " + r.raw());
                assertTrue(
                        r.elapsedMillis() < 1000,
                        "request " + i + " took " + r.elapsedMillis() + " ms");
                Thread.sleep(20);
            }
            assertTrue(server.stats().evicted() > 0, "attackers exceeded the cap");
            assertEquals(0, server.stats().busyRejected());
        } finally {
            running.set(false);
            for (Thread thread : threads) {
                thread.join(5000);
            }
        }
    }

    /** Keeps opening stalled connections, holding at most 50 at a time, until stopped. */
    private void attack(AtomicBoolean running, AtomicInteger opened) {
        byte[] stall = "GET /v1/health HTTP/1.1\r\nX-Stall: ".getBytes(StandardCharsets.US_ASCII);
        List<Socket> own = new ArrayList<>();
        try {
            while (running.get()) {
                Socket s = new Socket("127.0.0.1", client.port());
                s.getOutputStream().write(stall);
                own.add(s);
                opened.incrementAndGet();
                if (own.size() > 50) {
                    own.remove(0).close();
                }
                Thread.sleep(2);
            }
        } catch (IOException | InterruptedException e) {
            // test ends
        } finally {
            for (Socket s : own) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing
                }
            }
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long end = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(10);
        }
    }
}
