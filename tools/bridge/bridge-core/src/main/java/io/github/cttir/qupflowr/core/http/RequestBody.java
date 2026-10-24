/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.IOException;
import java.io.InputStream;

/**
 * Body of an admitted request. The stream yields exactly {@link #contentLength()} bytes and applies
 * the idle timeout and request deadline; a read past the deadline throws {@link
 * BodyTimeoutException}. Bodies are never buffered by the server.
 */
public final class RequestBody {

    private final InputStream stream;
    private final long contentLength;

    RequestBody(InputStream stream, long contentLength) {
        this.stream = stream;
        this.contentLength = contentLength;
    }

    /**
     * Returns the declared length.
     *
     * @return body length in bytes (already checked against the route limit)
     */
    public long contentLength() {
        return contentLength;
    }

    /**
     * Returns the body stream.
     *
     * @return stream over exactly {@link #contentLength()} bytes
     */
    public InputStream stream() {
        return stream;
    }

    /**
     * Reads a small body completely.
     *
     * @param maxBytes largest body the caller is willing to hold in memory
     * @return body bytes
     * @throws IOException on read failure or timeout
     * @throws IllegalStateException if the body is larger than {@code maxBytes}
     */
    public byte[] readAllBytes(int maxBytes) throws IOException {
        if (contentLength > maxBytes) {
            throw new IllegalStateException(
                    "Body of " + contentLength + " bytes exceeds " + maxBytes);
        }
        return stream.readNBytes((int) contentLength);
    }
}
