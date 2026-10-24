/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

import java.io.Serial;
import java.net.SocketTimeoutException;

/** The request body did not arrive within the idle timeout or the request deadline. */
public final class BodyTimeoutException extends SocketTimeoutException {

    @Serial private static final long serialVersionUID = 1L;

    private final String reason;

    BodyTimeoutException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * Returns the timeout reason.
     *
     * @return {@code idle_timeout} or {@code deadline_exceeded}
     */
    public String reason() {
        return reason;
    }
}
