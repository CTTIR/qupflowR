/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * Non-fatal warning attached to a response.
 *
 * @param code machine-readable warning code
 * @param messageKey i18n key of the user-facing message
 * @param message short English technical message
 */
public record ProtocolWarning(
        @SerializedName("code") String code,
        @SerializedName("message_key") String messageKey,
        @SerializedName("message") String message) {

    /**
     * Creates a warning.
     *
     * @param code code
     * @param messageKey message key
     * @param message message
     */
    public ProtocolWarning {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(messageKey, "messageKey");
        Objects.requireNonNull(message, "message");
    }
}
