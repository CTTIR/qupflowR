/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Response; the server adds {@code Content-Length} and {@code Connection: close} itself.
 *
 * @param status HTTP status code
 * @param headers additional headers (names must be tokens, values visible ASCII)
 * @param body response body, never null
 */
public record Response(int status, Map<String, String> headers, byte[] body) {

    /**
     * Creates a response.
     *
     * @param status status
     * @param headers headers (copied, order kept)
     * @param body body, null means empty
     */
    public Response {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Invalid status " + status);
        }
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(headers)));
        body = body == null ? new byte[0] : body;
    }

    /**
     * Returns a copy with one more header.
     *
     * @param name header name
     * @param value header value
     * @return new response
     */
    public Response withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        copy.put(name, value);
        return new Response(status, copy, body);
    }
}
