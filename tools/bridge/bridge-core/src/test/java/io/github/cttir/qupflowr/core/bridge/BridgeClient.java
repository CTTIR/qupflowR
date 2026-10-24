/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.github.cttir.qupflowr.core.http.RawHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Minimal raw-socket client for a running {@link BridgeServer} in tests. */
final class BridgeClient {

    private final int port;
    private final String token;

    BridgeClient(BridgeServer server) throws IOException {
        this.port = server.port();
        this.token = Files.readString(server.tokenPath()).trim();
    }

    static String readToken(Path tokenFile) throws IOException {
        return Files.readString(tokenFile).trim();
    }

    String token() {
        return token;
    }

    int port() {
        return port;
    }

    String host() {
        return "127.0.0.1:" + port;
    }

    RawHttp.Result get(String path) throws IOException {
        return send("GET", path, authorized(), null);
    }

    Map<String, String> authorized() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", host());
        headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    RawHttp.Result send(String method, String path, Map<String, String> headers, String body)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        headers.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        if (body != null) {
            sb.append("Content-Length: ")
                    .append(body.getBytes(StandardCharsets.UTF_8).length)
                    .append("\r\n");
        }
        sb.append("\r\n");
        if (body != null) {
            sb.append(body);
        }
        return RawHttp.exchange(port, sb.toString().getBytes(StandardCharsets.UTF_8), 10_000);
    }

    static JsonObject json(RawHttp.Result result) {
        return JsonParser.parseString(result.body()).getAsJsonObject();
    }
}
