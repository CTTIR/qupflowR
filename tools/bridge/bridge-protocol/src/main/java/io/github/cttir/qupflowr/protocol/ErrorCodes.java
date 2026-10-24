/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import java.util.List;

/**
 * Names of the error codes the bridge emits today. Status, retry hint and message key are never
 * duplicated here: they come from {@link ErrorCodeRegistry}. Tests check that every name exists in
 * the registry with the status required by the protocol contract.
 */
public final class ErrorCodes {

    /** 400: request line, header or target syntax is invalid. */
    public static final String MALFORMED_REQUEST = "MALFORMED_REQUEST";

    /** 401: missing or wrong bearer token. */
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";

    /** 403: foreign Host header, any Origin header or insufficient token scope. */
    public static final String SCOPE_DENIED = "SCOPE_DENIED";

    /** 404: no endpoint with this path. */
    public static final String ENDPOINT_NOT_FOUND = "ENDPOINT_NOT_FOUND";

    /** 405: endpoint exists but not for this method (response carries {@code Allow}). */
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";

    /** 408: request not received within the idle timeout or deadline. */
    public static final String REQUEST_TIMEOUT = "REQUEST_TIMEOUT";

    /** 411: {@code Transfer-Encoding} bodies are not supported. */
    public static final String LENGTH_REQUIRED = "LENGTH_REQUIRED";

    /** 413: declared body larger than the route limit ({@code details.limit_bytes}). */
    public static final String PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";

    /** 414: request line longer than the limit. */
    public static final String REQUEST_LINE_TOO_LONG = "REQUEST_LINE_TOO_LONG";

    /** 417: unsupported {@code Expect} header. */
    public static final String EXPECTATION_FAILED = "EXPECTATION_FAILED";

    /** 431: header block too large or too many header lines. */
    public static final String HEADERS_TOO_LARGE = "HEADERS_TOO_LARGE";

    /** 500: unexpected failure inside the host. */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    /** 503: connection limit reached ({@code Retry-After}). */
    public static final String HOST_BUSY = "HOST_BUSY";

    /** 505: HTTP version other than 1.0 or 1.1. */
    public static final String HTTP_VERSION_NOT_SUPPORTED = "HTTP_VERSION_NOT_SUPPORTED";

    private ErrorCodes() {}

    /**
     * Returns every code name defined in this class.
     *
     * @return code names
     */
    public static List<String> emitted() {
        return List.of(
                MALFORMED_REQUEST,
                AUTH_REQUIRED,
                SCOPE_DENIED,
                ENDPOINT_NOT_FOUND,
                METHOD_NOT_ALLOWED,
                REQUEST_TIMEOUT,
                LENGTH_REQUIRED,
                PAYLOAD_TOO_LARGE,
                REQUEST_LINE_TOO_LONG,
                EXPECTATION_FAILED,
                HEADERS_TOO_LARGE,
                INTERNAL_ERROR,
                HOST_BUSY,
                HTTP_VERSION_NOT_SUPPORTED);
    }
}
