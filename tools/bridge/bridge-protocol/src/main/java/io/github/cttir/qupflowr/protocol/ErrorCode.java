/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * One entry of the shared error-code registry ({@code inst/schema/protocol/v1/error-codes.json}).
 *
 * @param code stable machine-readable code, e.g. {@code AUTH_REQUIRED}
 * @param httpStatus HTTP status used by the bridge, or null for codes raised only by the R engine
 * @param rClass specific R condition class, e.g. {@code qp_connection_error}
 * @param retryable whether repeating the same request may succeed
 * @param messageKey i18n message key of the user-facing message
 * @param source contract row or evidence that defines the code (informational, may be null)
 */
public record ErrorCode(
        String code,
        Integer httpStatus,
        String rClass,
        boolean retryable,
        String messageKey,
        String source) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*");
    private static final Pattern R_CLASS = Pattern.compile("qp_[a-z]+(?:_[a-z]+)*");

    /**
     * Creates and validates an entry.
     *
     * @param code error code
     * @param httpStatus HTTP status or null
     * @param rClass R condition class
     * @param retryable retry hint
     * @param messageKey message key
     * @param source defining source or null
     * @throws IllegalArgumentException if a field violates the registry rules
     */
    public ErrorCode {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(rClass, "rClass");
        Objects.requireNonNull(messageKey, "messageKey");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("Invalid error code: " + code);
        }
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599)) {
            throw new IllegalArgumentException(
                    "Invalid HTTP status for " + code + ": " + httpStatus);
        }
        if (!R_CLASS.matcher(rClass).matches() || !rClass.endsWith("_error")) {
            throw new IllegalArgumentException(
                    "Invalid R condition class for " + code + ": " + rClass);
        }
        if (messageKey.isBlank()) {
            throw new IllegalArgumentException("Blank message key for " + code);
        }
    }

    /**
     * Returns the HTTP status as an optional value.
     *
     * @return the status, empty for engine-only codes
     */
    public OptionalInt status() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }
}
