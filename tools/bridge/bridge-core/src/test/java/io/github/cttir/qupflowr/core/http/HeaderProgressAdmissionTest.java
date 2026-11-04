/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real socket input with deliberately paused readers makes the admission race reproducible. */
@Timeout(15)
class HeaderProgressAdmissionTest {

    private static final byte[] PARTIAL =
            "GET /v1/health HTTP/1.1\r\nX-Stall: ".getBytes(StandardCharsets.US_ASCII);

    private LoopbackHttpServer server;
    private Thread acceptThread;

    private final HttpHandler handler =
            new HttpHandler() {
                @Override
                public Admission admit(RequestHead head) {
                    return Admission.accept(0, (h, body) -> response(200, "ok"));
                }

                @Override
                public Response transportError(RequestHead head, TransportError error) {
                    return response(error.status(), error.reason());
                }
            };

    private static Response response(int status, String body) {
        return new Response(status, Map.of(), body.getBytes(StandardCharsets.US_ASCII));
    }

    @BeforeEach
    void start() throws Exception {
        restart(Limits.defaults().withMaxConnections(1));
    }

    private void restart(Limits limits) throws Exception {
        restart(limits, HostPolicy.loopback());
    }

    private void restart(Limits limits, HostPolicy policy) throws Exception {
        if (server != null) {
            server.close();
        }
        server = LoopbackHttpServer.start(handler, null, limits, policy);
        Field field = LoopbackHttpServer.class.getDeclaredField("acceptThread");
        field.setAccessible(true);
        acceptThread = (Thread) field.get(server);
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private RawHttp.Result request() throws IOException {
        String text = "GET /ok HTTP/1.1\r\nHost: 127.0.0.1:" + server.port() + "\r\n\r\n";
        return RawHttp.exchange(server.port(), text.getBytes(StandardCharsets.US_ASCII), 3000);
    }

    private final class Peer implements AutoCloseable {
        final Socket client;
        final SocketChannel channel;
        final Connection connection;
        Thread reader;

        Peer(Socket client, SocketChannel channel) throws IOException {
            this.client = client;
            this.channel = channel;
            connection = new Connection(server, channel, System.nanoTime());
        }

        void startReader() {
            reader = Thread.ofVirtual().start(connection);
        }

        @Override
        public void close() throws IOException {
            server.table().release(connection);
            try {
                client.close();
            } finally {
                channel.close();
                if (reader != null) {
                    try {
                        reader.join(1000);
                        assertFalse(reader.isAlive(), "owned header reader must close");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while closing the owned reader", e);
                    }
                }
            }
        }
    }

    private Peer pendingPeer() throws Exception {
        // The peer uses a separate socket pair so its reader can be released deliberately.
        // The tested incoming request still traverses the real server accept loop and transport.
        try (ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.INET)) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            Socket client = new Socket("127.0.0.1", listener.socket().getLocalPort());
            SocketChannel channel = null;
            try {
                channel = listener.accept();
                Peer peer = new Peer(client, channel);
                assertEquals(
                        ConnectionTable.Outcome.ADMITTED,
                        server.table().admit(peer.connection).outcome());
                client.getOutputStream().write(PARTIAL);
                client.getOutputStream().flush();
                long started = System.nanoTime();
                while (channel.socket().getInputStream().available() < PARTIAL.length) {
                    assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
                    Thread.sleep(1);
                }
                assertEquals(1, server.stats().active());
                assertEquals(1, server.stats().headerPhase());
                return peer;
            } catch (Exception | AssertionError e) {
                client.close();
                if (channel != null) {
                    channel.close();
                }
                throw e;
            }
        }
    }

    private void awaitAdmissionDecision() throws InterruptedException {
        // On the baseline the completed FULL decision increments busyRejected. With the repair,
        // the accept thread cooperatively parks before that decision. Do not release the reader
        // before either event: otherwise a fast reader could let the broken baseline pass.
        long started = System.nanoTime();
        while (server.stats().busyRejected() == 0
                && acceptThread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3));
            Thread.sleep(1);
        }
    }

    @Test
    void pendingReaderCanMakeItsStalledConnectionEvictable() throws Exception {
        try (Peer peer = pendingPeer(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RawHttp.Result> result = pool.submit(this::request);
            awaitAdmissionDecision();
            peer.startReader();
            assertEquals(200, result.get(3, TimeUnit.SECONDS).status());
            assertEquals(1, server.stats().evicted());
            assertTrue(peer.connection.isEvicted());
            assertEquals(0, server.stats().busyRejected());
        }
    }

    @Test
    void fullyBufferedHeaderRemainsProtectedThroughFinalValidation() throws Exception {
        CountDownLatch validating = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        restart(
                Limits.defaults().withMaxConnections(1),
                (values, version, port) -> {
                    if (first.getAndSet(false)) {
                        validating.countDown();
                        try {
                            assertTrue(resume.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                    }
                    return HostPolicy.loopback().check(values, version, port);
                });
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RawHttp.Result> firstRequest = pool.submit(this::request);
            try {
                assertTrue(validating.await(3, TimeUnit.SECONDS));
                RawHttp.Result secondRequest = request();
                assertEquals(503, secondRequest.status(), secondRequest.raw());
                assertTrue(secondRequest.body().contains("connection_limit"));
                assertEquals(0, server.stats().evicted());
            } finally {
                resume.countDown();
            }
            assertEquals(200, firstRequest.get(3, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void slotCanBeReleasedWhileAdmissionWaitsOutsideTheTableMonitor() throws Exception {
        try (Peer peer = pendingPeer(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RawHttp.Result> result = pool.submit(this::request);
            awaitAdmissionDecision();
            // This synchronized operation must be able to complete while admission is waiting.
            server.table().release(peer.connection);
            assertEquals(200, result.get(3, TimeUnit.SECONDS).status());
            assertEquals(0, server.stats().evicted());
            assertEquals(0, server.stats().busyRejected());
        }
    }

    @Test
    void inputWithoutReaderProgressStillGetsABoundedBusyResponse() throws Exception {
        try (Peer peer = pendingPeer()) {
            RawHttp.Result result = request();
            assertEquals(503, result.status(), result.raw());
            assertTrue(result.elapsedMillis() < 1000, "admission must not wait indefinitely");
            assertEquals(1, server.stats().active());
            assertEquals(1, server.stats().headerPhase());
            assertEquals(0, server.stats().evicted());
            assertEquals(PARTIAL.length, peer.channel.socket().getInputStream().available());
        }
    }

    @Test
    void retryDoesNotExtendAShortHeaderDeadline() throws Exception {
        restart(
                Limits.defaults()
                        .withMaxConnections(1)
                        .withDeadlines(
                                Duration.ofSeconds(1),
                                Duration.ofMillis(20),
                                Duration.ofSeconds(1)));
        try (Peer peer = pendingPeer()) {
            RawHttp.Result result = request();
            assertEquals(503, result.status(), result.raw());
            assertTrue(result.elapsedMillis() < 200, "must not spend the full 250 ms retry budget");
            assertEquals(1, server.stats().active());
            assertFalse(peer.connection.isEvicted());
        }
    }

    @Test
    void shutdownClosesTheSocketWaitingForAdmission() throws Exception {
        try (Peer peer = pendingPeer(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RawHttp.Result> result = pool.submit(this::request);
            awaitAdmissionDecision();
            server.close();
            assertTrue(result.get(3, TimeUnit.SECONDS).closedWithoutResponse());
            assertTrue(server.isClosed());
            assertFalse(peer.connection.isEvicted());
        }
    }

    @Test
    void interruptionClosesTheWaitingSocketAndStopsTheServer() throws Exception {
        try (Peer peer = pendingPeer(); ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<RawHttp.Result> result = pool.submit(this::request);
            awaitAdmissionDecision();
            acceptThread.interrupt();
            assertTrue(result.get(3, TimeUnit.SECONDS).closedWithoutResponse());
            acceptThread.join(1000);
            assertTrue(server.isClosed());
            assertFalse(acceptThread.isAlive());
            assertFalse(peer.connection.isEvicted());
        }
    }
}
