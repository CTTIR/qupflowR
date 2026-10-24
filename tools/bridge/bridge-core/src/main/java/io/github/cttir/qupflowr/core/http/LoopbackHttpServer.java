/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small HTTP/1.1 server for the local bridge that uses only {@code java.base} (decision D019).
 *
 * <ul>
 *   <li>One IPv4 listening socket on 127.0.0.1 with a random port; accept loop on a platform daemon
 *       thread; one virtual thread per connection.
 *   <li>At most {@link Limits#maxConnections()} counted connections. When the cap is reached, the
 *       oldest connection that is stalled in its header block (no unread input) is evicted and
 *       receives {@code 503 HOST_BUSY}, so clients that hold connections open without completing a
 *       request cannot block complete, authenticated requests. If nothing is evictable, the new
 *       connection gets {@code 503 HOST_BUSY} with {@code Retry-After}.
 *   <li>Idle read timeout, absolute header deadline and absolute request deadline.
 *   <li>Request line, header block and header count limits; HTTP/1.0 and 1.1; strict CRLF framing;
 *       no {@code Transfer-Encoding}; body only via a single {@code Content-Length}.
 *   <li>Precedence: syntax and framing, Host, target, Expect (transport), then origin, token, route
 *       and method ({@link HttpHandler#admit}), then the route body limit, then {@code 100
 *       Continue}.
 *   <li>One request per connection; every response carries {@code Content-Length} and {@code
 *       Connection: close}. The connection slot is released as soon as the response is written;
 *       unread input is then discarded for a bounded time before closing.
 *   <li>Bodies are streamed to the handler, never buffered by the server.
 * </ul>
 */
public final class LoopbackHttpServer implements AutoCloseable {

    private final HttpHandler handler;
    private final ServerEvent.Listener listener;
    private final Limits limits;
    private final HostPolicy hostPolicy;
    private final ServerSocketChannel serverChannel;
    private final int port;
    private final ConnectionTable table;
    private final Semaphore busyPermits;
    private final Semaphore lingerPermits;
    private final ScheduledThreadPoolExecutor deadlines;
    private final Set<SocketChannel> open = ConcurrentHashMap.newKeySet();
    private final Thread acceptThread;
    private volatile boolean closed;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong responses = new AtomicLong();
    private final AtomicLong busyRejected = new AtomicLong();
    private final AtomicLong busyDropped = new AtomicLong();
    private final AtomicLong evicted = new AtomicLong();
    private final AtomicLong headerTimeouts = new AtomicLong();
    private final AtomicLong bodyTimeouts = new AtomicLong();
    private final AtomicLong deadlineClosed = new AtomicLong();
    private final AtomicLong clientClosed = new AtomicLong();
    private final AtomicLong handlerFailed = new AtomicLong();
    private final AtomicLong lingerSkipped = new AtomicLong();

    private LoopbackHttpServer(
            HttpHandler handler,
            ServerEvent.Listener listener,
            Limits limits,
            HostPolicy hostPolicy)
            throws IOException {
        this.handler = Objects.requireNonNull(handler, "handler");
        this.listener = listener == null ? event -> {} : listener;
        this.limits = Objects.requireNonNull(limits, "limits");
        this.hostPolicy = Objects.requireNonNull(hostPolicy, "hostPolicy");
        this.table = new ConnectionTable(limits.maxConnections());
        this.busyPermits = new Semaphore(limits.maxBusyResponders());
        this.lingerPermits = new Semaphore(limits.maxLingering());
        this.deadlines =
                new ScheduledThreadPoolExecutor(
                        1,
                        r -> {
                            Thread t = new Thread(r, "qupflowr-http-deadlines");
                            t.setDaemon(true);
                            return t;
                        });
        this.deadlines.setRemoveOnCancelPolicy(true);
        // IPv4-only socket: the listener is 127.0.0.1, never ::ffff:127.0.0.1 or ::1.
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.INET);
        try {
            InetAddress loopback = InetAddress.getByAddress("127.0.0.1", new byte[] {127, 0, 0, 1});
            channel.bind(new InetSocketAddress(loopback, 0), 256);
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            deadlines.shutdownNow();
            throw e;
        }
        this.serverChannel = channel;
        this.port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        this.acceptThread = new Thread(this::acceptLoop, "qupflowr-http-accept");
        this.acceptThread.setDaemon(true);
    }

    /**
     * Binds to {@code 127.0.0.1} on a random free port and starts accepting.
     *
     * @param handler request handler
     * @param listener optional event listener (may be null)
     * @param limits limits and deadlines
     * @param hostPolicy Host header policy, normally {@link HostPolicy#loopback()}
     * @return the running server
     * @throws IOException if the socket cannot be bound
     */
    public static LoopbackHttpServer start(
            HttpHandler handler,
            ServerEvent.Listener listener,
            Limits limits,
            HostPolicy hostPolicy)
            throws IOException {
        LoopbackHttpServer server = new LoopbackHttpServer(handler, listener, limits, hostPolicy);
        server.acceptThread.start();
        return server;
    }

    /**
     * Returns the bound port on 127.0.0.1.
     *
     * @return port
     */
    public int port() {
        return port;
    }

    /**
     * Returns the configured limits.
     *
     * @return limits
     */
    public Limits limits() {
        return limits;
    }

    /**
     * Returns a snapshot of the counters.
     *
     * @return statistics
     */
    public ServerStats stats() {
        return new ServerStats(
                accepted.get(),
                table.countedSize(),
                table.headerPhaseSize(),
                open.size(),
                responses.get(),
                busyRejected.get(),
                busyDropped.get(),
                evicted.get(),
                headerTimeouts.get(),
                bodyTimeouts.get(),
                deadlineClosed.get(),
                clientClosed.get(),
                handlerFailed.get(),
                lingerSkipped.get());
    }

    /**
     * Tells whether {@link #close()} was called.
     *
     * @return true after close
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops accepting and closes the listener, all open connections and the scheduler. Idempotent.
     */
    @Override
    public void close() {
        closed = true;
        closeQuietly(serverChannel);
        for (SocketChannel channel : open) {
            closeQuietly(channel);
        }
        deadlines.shutdownNow();
        try {
            acceptThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------ accept

    private void acceptLoop() {
        while (!closed) {
            SocketChannel channel;
            try {
                channel = serverChannel.accept();
            } catch (IOException e) {
                if (closed) {
                    return;
                }
                pauseAfterAcceptFailure();
                continue;
            }
            accepted.incrementAndGet();
            long acceptedAt = System.nanoTime();
            Connection connection;
            try {
                connection = new Connection(this, channel, acceptedAt);
            } catch (IOException e) {
                closeQuietly(channel);
                continue;
            }
            ConnectionTable.Admit admit = table.admit(connection);
            switch (admit.outcome()) {
                case ADMITTED, ADMITTED_BY_EVICTION -> {
                    if (admit.victim() != null) {
                        evicted.incrementAndGet();
                        admit.victim().interruptHeaderPhase();
                    }
                    startConnection(connection);
                }
                case FULL -> rejectBusy(channel, acceptedAt);
                default -> throw new IllegalStateException("Unknown admission outcome");
            }
        }
    }

    private void startConnection(Connection connection) {
        SocketChannel channel = connection.channel();
        open.add(channel);
        try {
            long delay =
                    connection.acceptedAt()
                            + limits.requestDeadline().toNanos()
                            - System.nanoTime();
            connection.setDeadline(
                    deadlines.schedule(
                            () -> {
                                if (channel.isOpen()) {
                                    deadlineClosed.incrementAndGet();
                                    closeQuietly(channel);
                                }
                            },
                            Math.max(0, delay),
                            TimeUnit.NANOSECONDS));
            Thread.ofVirtual().name("qupflowr-http-connection").start(connection);
        } catch (RuntimeException | OutOfMemoryError e) {
            table.release(connection);
            forget(connection);
        }
    }

    private void rejectBusy(SocketChannel channel, long acceptedAt) {
        if (!busyPermits.tryAcquire()) {
            busyDropped.incrementAndGet();
            closeQuietly(channel);
            emit(ServerEvent.Type.BUSY_DROPPED, 0, null, null, acceptedAt, null);
            return;
        }
        busyRejected.incrementAndGet();
        try {
            Thread.ofVirtual()
                    .name("qupflowr-http-busy")
                    .start(
                            () -> {
                                try {
                                    TransportError error =
                                            new TransportError(
                                                    503,
                                                    TransportCodes.HOST_BUSY,
                                                    "connection_limit",
                                                    true,
                                                    null);
                                    Response response =
                                            handler.transportError(null, error)
                                                    .withHeader("Retry-After", "1");
                                    ResponseWriter.checkHeaders(response);
                                    ResponseWriter.write(channel.socket(), response, false);
                                    emit(
                                            ServerEvent.Type.BUSY_REJECTED,
                                            503,
                                            null,
                                            null,
                                            acceptedAt,
                                            error.reason());
                                    discardInput(
                                            channel.socket(),
                                            System.nanoTime() + limits.lingerTimeout().toNanos());
                                } catch (IOException | RuntimeException e) {
                                    // client went away
                                } finally {
                                    closeQuietly(channel);
                                    busyPermits.release();
                                }
                            });
        } catch (RuntimeException | OutOfMemoryError e) {
            busyPermits.release();
            closeQuietly(channel);
        }
    }

    private static void pauseAfterAcceptFailure() {
        try {
            Thread.sleep(20); // e.g. file descriptor exhaustion: do not spin
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------
    // connection support

    HttpHandler handler() {
        return handler;
    }

    HostPolicy hostPolicy() {
        return hostPolicy;
    }

    ConnectionTable table() {
        return table;
    }

    void forget(Connection connection) {
        closeQuietly(connection.channel());
        open.remove(connection.channel());
    }

    /**
     * Half-closes and discards unread input for a bounded time so the client reads the response.
     */
    void linger(Connection connection, long lingerEndNanos) {
        if (!lingerPermits.tryAcquire()) {
            lingerSkipped.incrementAndGet();
            emit(ServerEvent.Type.LINGER_SKIPPED, 0, null, null, connection.acceptedAt(), null);
            return;
        }
        try {
            discardInput(connection.channel().socket(), lingerEndNanos);
        } finally {
            lingerPermits.release();
        }
    }

    private void discardInput(Socket socket, long endNanos) {
        try {
            socket.shutdownOutput();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            long discarded = 0;
            while (discarded < limits.lingerMaxBytes()) {
                long remainingMillis = TimeUnit.NANOSECONDS.toMillis(endNanos - System.nanoTime());
                if (remainingMillis <= 0) {
                    return;
                }
                socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remainingMillis));
                int n = in.read(buffer);
                if (n < 0) {
                    return;
                }
                discarded += n;
            }
        } catch (IOException e) {
            // timeout or reset: nothing more to do
        }
    }

    void emit(
            ServerEvent.Type type,
            int status,
            String method,
            String path,
            long acceptedAt,
            String reason) {
        try {
            listener.onEvent(
                    new ServerEvent(
                            type,
                            status,
                            method,
                            path,
                            (System.nanoTime() - acceptedAt) / 1e6,
                            reason));
        } catch (RuntimeException e) {
            // listeners must not break the server
        }
    }

    void countResponse() {
        responses.incrementAndGet();
    }

    void countHeaderTimeout() {
        headerTimeouts.incrementAndGet();
    }

    void countBodyTimeout() {
        bodyTimeouts.incrementAndGet();
    }

    void countClientClosed() {
        clientClosed.incrementAndGet();
    }

    void countHandlerFailed() {
        handlerFailed.incrementAndGet();
    }

    static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException e) {
            // ignore
        }
    }
}
