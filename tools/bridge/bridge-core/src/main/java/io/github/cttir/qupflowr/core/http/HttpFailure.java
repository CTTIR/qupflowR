/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.Serial;

/** Ends an exchange with a transport error response, or silently when {@link #error} is null. */
final class HttpFailure extends Exception {

    @Serial private static final long serialVersionUID = 1L;

    /** Error to render, or null to close without response. */
    final transient TransportError error;

    /** Event to report (RESPONSE for ordinary protocol errors). */
    final ServerEvent.Type event;

    HttpFailure(TransportError error, ServerEvent.Type event) {
        super(error == null ? "closed" : error.reason(), null, false, false);
        this.error = error;
        this.event = event;
    }

    static HttpFailure of(int status, String code, String reason) {
        return new HttpFailure(TransportError.of(status, code, reason), ServerEvent.Type.RESPONSE);
    }

    static HttpFailure clientClosed() {
        return new HttpFailure(null, ServerEvent.Type.CLIENT_CLOSED);
    }
}
