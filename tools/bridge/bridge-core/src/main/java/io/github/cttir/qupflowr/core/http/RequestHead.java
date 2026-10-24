/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

/**
 * Parsed and validated request line and headers. No body byte has been read when a handler sees it.
 *
 * @param method request method (case-sensitive token)
 * @param target raw request target
 * @param path validated path without query (no percent-encoding, dot or empty segments)
 * @param query raw query string, or null; never decoded and never used for routing
 * @param version {@code HTTP/1.1} or {@code HTTP/1.0}
 * @param headers request headers
 * @param contentLength declared body length, 0 if absent
 */
public record RequestHead(
        String method,
        String target,
        String path,
        String query,
        String version,
        Headers headers,
        long contentLength) {}
