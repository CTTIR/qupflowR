/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.http;

/**
 * Error codes produced by the transport. They are plain strings so this package depends on {@code
 * java.base} only; the bridge checks them against the protocol registry.
 */
public final class TransportCodes {

    /** 400. */
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";

    /** 403 (Host policy). */
    public static final String SCOPE_DENIED = "SCOPE_DENIED";

    /** 408. */
    public static final String REQUEST_TIMEOUT = "REQUEST_TIMEOUT";

    /** 411. */
    public static final String LENGTH_REQUIRED = "LENGTH_REQUIRED";

    /** 413. */
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    /** 414. */
    public static final String REQUEST_LINE_TOO_LONG = "REQUEST_LINE_TOO_LONG";

    /** 417. */
    public static final String EXPECTATION_FAILED = "EXPECTATION_FAILED";

    /** 431. */
    public static final String HEADERS_TOO_LARGE = "HEADERS_TOO_LARGE";

    /** 500. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** 503. */
    public static final String HOST_BUSY = "HOST_BUSY";

    /** 505. */
    public static final String HTTP_VERSION_NOT_SUPPORTED = "HTTP_VERSION_NOT_SUPPORTED";

    private TransportCodes() {}
}
