/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One request/response exchange on one socket, run on its own virtual thread.
 *
 * <p>Life cycle: header phase (evictable), admission, optional {@code 100 Continue} and body,
 * response, slot released, bounded linger, close.
 */
final class Connection implements Runnable {

    private final LoopbackHttpServer server;
    private final SocketChannel channel;
    private final InputStream pendingInput;
    private final long acceptedAt;
    private final java.util.concurrent.atomic.AtomicLong received =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean evicted;
    private volatile ScheduledFuture<?> deadline;

    Connection(LoopbackHttpServer server, SocketChannel channel, long acceptedAt)
            throws IOException {
        this.server = server;
        this.channel = channel;
        this.pendingInput = channel.socket().getInputStream();
        this.acceptedAt = acceptedAt;
    }

    SocketChannel channel() {
        return channel;
    }

    long acceptedAt() {
        return acceptedAt;
    }

    /** Marks the connection evicted; called under the table lock. */
    void markEvicted() {
        evicted = true;
    }

    boolean isEvicted() {
        return evicted;
    }

    /** Counts bytes read from the socket. */
    void addReceived(int count) {
        received.addAndGet(count);
    }

    /**
     * Tells whether the connection may be evicted: it is waiting for the client (no received byte
     * is left unread) and it has either sent something already or stayed silent longer than the
     * grace period. Called by the accept thread under the table lock while the connection thread
     * may be blocked in a read.
     */
    boolean isEvictable(long nowNanos) {
        boolean spoke = received.get() > 0;
        if (!spoke && nowNanos - acceptedAt < server.limits().evictionGrace().toNanos()) {
            return false;
        }
        try {
            return pendingInput.available() == 0;
        } catch (IOException e) {
            return true;
        }
    }

    /** Wakes the connection's reader so it answers {@code 503 HOST_BUSY} and closes. */
    void interruptHeaderPhase() {
        try {
            channel.shutdownInput();
        } catch (IOException | RuntimeException e) {
            LoopbackHttpServer.closeQuietly(channel);
        }
    }

    void setDeadline(ScheduledFuture<?> future) {
        this.deadline = future;
    }

    @Override
    public void run() {
        Limits limits = server.limits();
        Socket socket = channel.socket();
        RequestHead head = null;
        try {
            socket.setTcpNoDelay(true);
            ConnectionInput in =
                    new ConnectionInput(
                            socket, limits.idleReadTimeout().toMillis(), this::addReceived);
            long headerDeadline = acceptedAt + limits.headerDeadline().toNanos();
            long requestDeadline = acceptedAt + limits.requestDeadline().toNanos();
            try {
                head =
                        RequestParser.readHead(
                                in, limits, server.hostPolicy(), server.port(), headerDeadline);
            } catch (HttpFailure failure) {
                if (evicted) {
                    respondEvicted();
                } else {
                    fail(null, failure, requestDeadline);
                }
                return;
            }
            if (head == null) {
                if (evicted) {
                    respondEvicted();
                } else {
                    server.countClientClosed();
                    server.emit(ServerEvent.Type.CLIENT_CLOSED, 0, null, null, acceptedAt, null);
                }
                return;
            }
            if (!server.table().leaveHeaderPhase(this)) {
                respondEvicted();
                return;
            }
            exchange(head, in, requestDeadline);
        } catch (IOException | RuntimeException e) {
            // Closed by the request deadline, by server shutdown or by the client.
            ScheduledFuture<?> d = deadline;
            if (!server.isClosed() && d != null && d.isDone() && !d.isCancelled()) {
                server.emit(
                        ServerEvent.Type.DEADLINE_CLOSED,
                        0,
                        method(head),
                        path(head),
                        acceptedAt,
                        null);
            }
        } finally {
            ScheduledFuture<?> d = deadline;
            if (d != null) {
                d.cancel(false);
            }
            server.table().release(this);
            server.forget(this);
        }
    }

    private void exchange(RequestHead head, ConnectionInput in, long requestDeadline)
            throws IOException {
        Admission admission;
        try {
            admission = Objects.requireNonNull(server.handler().admit(head), "admission");
        } catch (RuntimeException e) {
            handlerFailed(head, e, requestDeadline);
            return;
        }
        if (admission instanceof Admission.Rejected rejected) {
            respond(head, rejected.response(), requestDeadline, true);
            return;
        }
        Admission.Accepted accepted = (Admission.Accepted) admission;
        long bodyLimit = Math.min(accepted.maxBodyBytes(), server.limits().maxBodyBytes());
        if (head.contentLength() > bodyLimit) {
            TransportError error =
                    new TransportError(
                            413,
                            TransportCodes.PAYLOAD_TOO_LARGE,
                            "content_length_exceeds_limit",
                            false,
                            bodyLimit);
            respond(head, server.handler().transportError(head, error), requestDeadline, true);
            return;
        }
        if (head.contentLength() > 0
                && "HTTP/1.1".equals(head.version())
                && head.headers().contains("Expect")) {
            socket().getOutputStream().write(ResponseWriter.CONTINUE_100);
            socket().getOutputStream().flush();
        }
        InputStream bodyStream = in.body(head.contentLength(), requestDeadline);
        Response response;
        try {
            response =
                    Objects.requireNonNull(
                            accepted.exchange()
                                    .handle(
                                            head,
                                            new RequestBody(bodyStream, head.contentLength())),
                            "response");
        } catch (BodyTimeoutException e) {
            server.countBodyTimeout();
            TransportError error =
                    new TransportError(408, TransportCodes.REQUEST_TIMEOUT, e.reason(), true, null);
            server.emit(
                    ServerEvent.Type.BODY_TIMEOUT,
                    408,
                    head.method(),
                    head.path(),
                    acceptedAt,
                    e.reason());
            respond(head, server.handler().transportError(head, error), requestDeadline, false);
            return;
        } catch (EOFException e) {
            server.countClientClosed();
            server.emit(
                    ServerEvent.Type.CLIENT_CLOSED,
                    0,
                    head.method(),
                    head.path(),
                    acceptedAt,
                    null);
            return;
        } catch (Exception e) {
            handlerFailed(head, e, requestDeadline);
            return;
        }
        respond(head, response, requestDeadline, true);
    }

    private void handlerFailed(RequestHead head, Exception e, long requestDeadline)
            throws IOException {
        server.countHandlerFailed();
        server.emit(
                ServerEvent.Type.HANDLER_FAILED,
                500,
                method(head),
                path(head),
                acceptedAt,
                e.getClass().getSimpleName());
        TransportError error =
                TransportError.of(500, TransportCodes.INTERNAL_ERROR, "handler_failed");
        respond(head, server.handler().transportError(head, error), requestDeadline, false);
    }

    private void fail(RequestHead head, HttpFailure failure, long requestDeadline)
            throws IOException {
        if (failure.event == ServerEvent.Type.HEADER_TIMEOUT) {
            server.countHeaderTimeout();
        } else if (failure.event == ServerEvent.Type.CLIENT_CLOSED) {
            server.countClientClosed();
        }
        if (failure.error == null) {
            server.emit(failure.event, 0, method(head), path(head), acceptedAt, null);
            return;
        }
        if (failure.event != ServerEvent.Type.RESPONSE) {
            server.emit(
                    failure.event,
                    failure.error.status(),
                    method(head),
                    path(head),
                    acceptedAt,
                    failure.error.reason());
        }
        respond(head, server.handler().transportError(head, failure.error), requestDeadline, false);
    }

    private void respond(
            RequestHead head, Response response, long requestDeadline, boolean fromHandler)
            throws IOException {
        Response checked = response;
        try {
            ResponseWriter.checkHeaders(response);
        } catch (IllegalArgumentException e) {
            if (!fromHandler) {
                throw e;
            }
            server.countHandlerFailed();
            server.emit(
                    ServerEvent.Type.HANDLER_FAILED,
                    500,
                    method(head),
                    path(head),
                    acceptedAt,
                    "invalid_response_header");
            checked =
                    server.handler()
                            .transportError(
                                    head,
                                    TransportError.of(
                                            500, TransportCodes.INTERNAL_ERROR, "handler_failed"));
            ResponseWriter.checkHeaders(checked);
        }
        ResponseWriter.write(socket(), checked, head != null && "HEAD".equals(head.method()));
        server.countResponse();
        server.emit(
                ServerEvent.Type.RESPONSE,
                checked.status(),
                method(head),
                path(head),
                acceptedAt,
                null);
        // The slot is released before lingering, so clients that are slow to close do not hold it.
        server.table().release(this);
        server.linger(
                this,
                Math.min(
                        requestDeadline,
                        System.nanoTime() + server.limits().lingerTimeout().toNanos()));
    }

    private void respondEvicted() throws IOException {
        TransportError error =
                new TransportError(
                        503, TransportCodes.HOST_BUSY, "evicted_header_phase", true, null);
        server.emit(ServerEvent.Type.EVICTED, 503, null, null, acceptedAt, error.reason());
        Response response =
                server.handler().transportError(null, error).withHeader("Retry-After", "1");
        ResponseWriter.checkHeaders(response);
        ResponseWriter.write(socket(), response, false);
    }

    private Socket socket() {
        return channel.socket();
    }

    private static String method(RequestHead head) {
        return head == null ? null : head.method();
    }

    private static String path(RequestHead head) {
        return head == null ? null : head.path();
    }

    long elapsedMillis() {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - acceptedAt);
    }
}
