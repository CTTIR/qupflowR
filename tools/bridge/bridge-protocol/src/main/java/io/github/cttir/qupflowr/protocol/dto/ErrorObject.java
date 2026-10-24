/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import io.github.cttir.qupflowr.protocol.ErrorCode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Error member of an {@link ErrorEnvelope}. Details never contain tokens or unbounded file content.
 *
 * @param code registry error code
 * @param messageKey i18n key of the user-facing message
 * @param message short English technical message (clients localise via {@code message_key})
 * @param details machine-readable details, e.g. {@code reason} or {@code limit_bytes}
 * @param retryable whether repeating the request may succeed
 */
public record ErrorObject(
        @SerializedName("code") String code,
        @SerializedName("message_key") String messageKey,
        @SerializedName("message") String message,
        @SerializedName("details") Map<String, Object> details,
        @SerializedName("retryable") boolean retryable) {

    /**
     * Creates an error object.
     *
     * @param code code
     * @param messageKey message key
     * @param message message
     * @param details details (copied, insertion order kept)
     * @param retryable retry hint
     */
    public ErrorObject {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(message, "message");
        details =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(details, "details")));
    }

    /**
     * Builds an error object from a registry entry.
     *
     * @param entry registry entry
     * @param message short English technical message
     * @param details machine-readable details
     * @return the error object with code, message key and retry hint taken from the registry
     */
    public static ErrorObject of(ErrorCode entry, String message, Map<String, Object> details) {
        return new ErrorObject(
                entry.code(), entry.messageKey(), message, details, entry.retryable());
    }
}
