/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

/**
 * Failure detected by the transport itself; rendered into a response by {@link
 * HttpHandler#transportError(RequestHead, TransportError)}.
 *
 * @param status HTTP status
 * @param code protocol error code (see {@link TransportCodes})
 * @param reason machine-readable detail, e.g. {@code duplicate_host}
 * @param retryable whether the same request may succeed later
 * @param limitBytes the violated size limit for {@code 413}, otherwise null
 */
public record TransportError(
        int status, String code, String reason, boolean retryable, Long limitBytes) {

    /**
     * Creates a non-retryable error without a size limit.
     *
     * @param status HTTP status
     * @param code error code
     * @param reason reason
     * @return the error
     */
    public static TransportError of(int status, String code, String reason) {
        return new TransportError(status, code, reason, false, null);
    }
}
