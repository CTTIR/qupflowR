/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;

/**
 * Failed protocol response: carries {@code error} instead of {@code data}.
 *
 * @param protocolVersion protocol version of the host
 * @param requestId request id
 * @param instanceId bridge instance UUID
 * @param error error description
 * @param warnings non-fatal warnings (may be empty, never null)
 */
public record ErrorEnvelope(
        @SerializedName("protocol_version") String protocolVersion,
        @SerializedName("request_id") String requestId,
        @SerializedName("instance_id") String instanceId,
        @SerializedName("error") ErrorObject error,
        @SerializedName("warnings") List<ProtocolWarning> warnings) {

    /**
     * Creates an envelope.
     *
     * @param protocolVersion protocol version
     * @param requestId request id
     * @param instanceId instance id
     * @param error error
     * @param warnings warnings
     */
    public ErrorEnvelope {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(error, "error");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
