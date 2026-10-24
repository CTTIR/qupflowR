/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Serialises responses: status line, validated headers, Content-Length, Connection: close. */
final class ResponseWriter {

    static final byte[] CONTINUE_100 =
            "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private static final Set<String> RESERVED =
            Set.of("content-length", "connection", "transfer-encoding");

    private ResponseWriter() {}

    /** Rejects header names that are not tokens, reserved names and values with control bytes. */
    static void checkHeaders(Response response) {
        for (Map.Entry<String, String> e : response.headers().entrySet()) {
            if (!RequestParser.isToken(e.getKey())
                    || RESERVED.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Invalid response header name");
            }
            String value = e.getValue();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if ((c < 0x20 && c != '\t') || c >= 0x7F) {
                    throw new IllegalArgumentException("Invalid response header value");
                }
            }
        }
    }

    static void write(Socket socket, Response response, boolean omitBody) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append("HTTP/1.1 ")
                .append(response.status())
                .append(' ')
                .append(reasonPhrase(response.status()))
                .append("\r\n");
        for (Map.Entry<String, String> e : response.headers().entrySet()) {
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        sb.append("Content-Length: ").append(response.body().length).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        byte[] head = sb.toString().getBytes(StandardCharsets.ISO_8859_1);
        OutputStream out = socket.getOutputStream();
        if (omitBody || response.body().length == 0) {
            out.write(head);
        } else {
            byte[] all = new byte[head.length + response.body().length];
            System.arraycopy(head, 0, all, 0, head.length);
            System.arraycopy(response.body(), 0, all, head.length, response.body().length);
            out.write(all);
        }
        out.flush();
    }

    static String reasonPhrase(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 204 -> "No Content";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 413 -> "Content Too Large";
            case 414 -> "URI Too Long";
            case 417 -> "Expectation Failed";
            case 422 -> "Unprocessable Content";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 503 -> "Service Unavailable";
            case 505 -> "HTTP Version Not Supported";
            default -> "Status";
        };
    }
}
