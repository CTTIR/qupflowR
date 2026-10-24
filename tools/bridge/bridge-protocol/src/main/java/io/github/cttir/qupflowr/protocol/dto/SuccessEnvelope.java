/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;

/**
 * Successful protocol response.
 *
 * @param protocolVersion protocol version of the host
 * @param requestId request id (the client's {@code X-Request-Id} UUID or a generated one)
 * @param instanceId bridge instance UUID
 * @param data endpoint-specific payload
 * @param warnings non-fatal warnings (may be empty, never null)
 * @param <T> payload type
 */
public record SuccessEnvelope<T>(
        @SerializedName("protocol_version") String protocolVersion,
        @SerializedName("request_id") String requestId,
        @SerializedName("instance_id") String instanceId,
        @SerializedName("data") T data,
        @SerializedName("warnings") List<ProtocolWarning> warnings) {

    /**
     * Creates an envelope.
     *
     * @param protocolVersion protocol version
     * @param requestId request id
     * @param instanceId instance id
     * @param data payload
     * @param warnings warnings
     */
    public SuccessEnvelope {
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(instanceId, "instanceId");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
