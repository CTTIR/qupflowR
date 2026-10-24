/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Raw socket HTTP client for tests: sends bytes exactly as given and reads until the server closes.
 */
public final class RawHttp {

    /**
     * Exchange result.
     *
     * @param status status of the final response, -1 if none was received
     * @param head response head without the final CRLFCRLF
     * @param body response body decoded as UTF-8
     * @param raw all received bytes as ISO-8859-1 text
     * @param elapsedMillis time until the server closed or the read timed out
     * @param timedOut the read timed out
     * @param reset the connection was reset
     */
    public record Result(
            int status,
            String head,
            String body,
            String raw,
            long elapsedMillis,
            boolean timedOut,
            boolean reset) {
        /**
         * Tells whether no response was received.
         *
         * @return true if no status line was read
         */
        public boolean closedWithoutResponse() {
            return status < 0;
        }

        /**
         * Returns a response header value.
         *
         * @param name header name (case-insensitive)
         * @return value or null
         */
        public String header(String name) {
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }
    }

    private RawHttp() {}

    /**
     * Sends a request and reads the response.
     *
     * @param port server port
     * @param request request text (ISO-8859-1)
     * @return result
     * @throws IOException on connect failure
     */
    public static Result exchange(int port, String request) throws IOException {
        return exchange(port, request.getBytes(StandardCharsets.ISO_8859_1), 10_000);
    }

    /**
     * Sends a request and reads the response.
     *
     * @param port server port
     * @param request request bytes
     * @param readTimeoutMillis read timeout
     * @return result
     * @throws IOException on connect failure
     */
    public static Result exchange(int port, byte[] request, int readTimeoutMillis)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            long start = System.nanoTime();
            socket.setSoTimeout(readTimeoutMillis);
            try {
                socket.getOutputStream().write(request);
                socket.getOutputStream().flush();
            } catch (SocketException e) {
                // the server may already have answered and closed (e.g. oversized request line)
            }
            return readResponse(socket, start);
        }
    }

    /**
     * Reads until the server closes.
     *
     * @param socket connected socket
     * @param startNanos start of the measurement
     * @return result
     * @throws IOException on unexpected failures
     */
    public static Result readResponse(Socket socket, long startNanos) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean timedOut = false;
        boolean reset = false;
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[8192];
        try {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                bytes.write(buffer, 0, n);
            }
        } catch (SocketTimeoutException e) {
            timedOut = true;
        } catch (SocketException e) {
            reset = true;
        }
        long elapsed = (System.nanoTime() - startNanos) / 1_000_000;
        String raw = bytes.toString(StandardCharsets.ISO_8859_1);
        String rest = raw;
        while (rest.startsWith("HTTP/1.1 100 ")) {
            int end = rest.indexOf("\r\n\r\n");
            rest = end < 0 ? "" : rest.substring(end + 4);
        }
        int status = -1;
        String head = "";
        String body = "";
        if (rest.startsWith("HTTP/1.1 ") && rest.length() >= 12) {
            status = Integer.parseInt(rest.substring(9, 12));
            int split = rest.indexOf("\r\n\r\n");
            head = split < 0 ? rest : rest.substring(0, split);
            body =
                    split < 0
                            ? ""
                            : new String(
                                    rest.substring(split + 4).getBytes(StandardCharsets.ISO_8859_1),
                                    StandardCharsets.UTF_8);
        }
        return new Result(status, head, body, raw, elapsed, timedOut, reset);
    }

    /**
     * Reads one complete response (head plus Content-Length body) without waiting for the close.
     *
     * @param socket connected socket with a read timeout
     * @return response text
     * @throws IOException on timeout or close before the body is complete
     */
    public static String readOneResponse(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int contentLength = -1;
        int headEnd = -1;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed before a complete response");
            }
            bytes.write(b);
            String text = bytes.toString(StandardCharsets.ISO_8859_1);
            if (headEnd < 0 && text.endsWith("\r\n\r\n")) {
                headEnd = text.length();
                for (String line : text.split("\r\n")) {
                    if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }
            }
            if (headEnd >= 0 && text.length() - headEnd >= contentLength) {
                return text;
            }
        }
    }
}
