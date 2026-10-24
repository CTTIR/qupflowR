/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * Buffered, bounded reader over one socket. Every read uses a timeout of {@code min(idle timeout,
 * time left until the phase deadline)}.
 */
final class ConnectionInput {

    private final Socket socket;
    private final InputStream in;
    private final long idleMillis;
    private final java.util.function.IntConsumer receivedCounter;
    private final byte[] buffer = new byte[8192];
    private int pos;
    private int limit;
    private int lastLineBytes;

    ConnectionInput(Socket socket, long idleMillis, java.util.function.IntConsumer receivedCounter)
            throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.idleMillis = idleMillis;
        this.receivedCounter = receivedCounter;
    }

    int lastLineBytes() {
        return lastLineBytes;
    }

    /**
     * Reads one CRLF-terminated line of at most {@code maxBytes} bytes including CRLF.
     *
     * @return the line without CRLF, or null if the stream ended before the first byte of the line
     */
    String readLine(int maxBytes, long deadline, boolean requestLine)
            throws IOException, HttpFailure {
        StringBuilder line = new StringBuilder(64);
        int count = 0;
        boolean sawCr = false;
        while (true) {
            if (pos == limit && !fill(deadline)) {
                if (count == 0) {
                    return null;
                }
                throw HttpFailure.clientClosed();
            }
            int b = buffer[pos++] & 0xFF;
            count++;
            if (sawCr) {
                if (b != '\n') {
                    throw HttpFailure.of(400, TransportCodes.MALFORMED_REQUEST, "bare_cr");
                }
                lastLineBytes = count;
                return line.toString();
            }
            if (b == '\r') {
                sawCr = true;
            } else if (b == '\n') {
                throw HttpFailure.of(400, TransportCodes.MALFORMED_REQUEST, "bare_lf");
            } else {
                line.append((char) b);
            }
            // A terminating LF returns above, so reaching the limit here means CRLF cannot fit.
            if (count >= maxBytes) {
                throw requestLine
                        ? HttpFailure.of(
                                414, TransportCodes.REQUEST_LINE_TOO_LONG, "request_line_too_long")
                        : HttpFailure.of(
                                431, TransportCodes.HEADERS_TOO_LARGE, "header_block_too_large");
            }
        }
    }

    /** Returns a stream over exactly {@code length} body bytes. */
    InputStream body(long length, long deadline) {
        return new BodyStream(length, deadline);
    }

    private boolean fill(long deadline) throws IOException, HttpFailure {
        int n;
        try {
            n = timedRead(buffer, 0, buffer.length, deadline);
        } catch (BodyTimeoutException e) {
            throw new HttpFailure(
                    new TransportError(408, TransportCodes.REQUEST_TIMEOUT, e.reason(), true, null),
                    ServerEvent.Type.HEADER_TIMEOUT);
        }
        if (n < 0) {
            return false;
        }
        pos = 0;
        limit = n;
        return true;
    }

    private int timedRead(byte[] dst, int off, int len, long deadline) throws IOException {
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        long timeoutMillis = Math.min(idleMillis, remainingMillis);
        if (timeoutMillis <= 0) {
            throw new BodyTimeoutException("deadline_exceeded");
        }
        socket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, timeoutMillis));
        try {
            int n = in.read(dst, off, len);
            if (n > 0) {
                receivedCounter.accept(n);
            }
            return n;
        } catch (SocketTimeoutException e) {
            throw new BodyTimeoutException(
                    timeoutMillis < idleMillis ? "deadline_exceeded" : "idle_timeout");
        }
    }

    /** Stream of exactly {@code length} bytes; buffered header-phase bytes are consumed first. */
    private final class BodyStream extends InputStream {
        private long remaining;
        private final long deadline;

        BodyStream(long length, long deadline) {
            this.remaining = length;
            this.deadline = deadline;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] dst, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (remaining <= 0) {
                return -1;
            }
            int want = (int) Math.min(len, remaining);
            int n;
            if (pos < limit) {
                n = Math.min(want, limit - pos);
                System.arraycopy(buffer, pos, dst, off, n);
                pos += n;
            } else {
                n = timedRead(dst, off, want, deadline);
                if (n < 0) {
                    throw new EOFException("client closed before the declared body length");
                }
            }
            remaining -= n;
            return n;
        }

        @Override
        public int available() {
            return (int) Math.min(Integer.MAX_VALUE, Math.min(remaining, limit - pos));
        }
    }
}
