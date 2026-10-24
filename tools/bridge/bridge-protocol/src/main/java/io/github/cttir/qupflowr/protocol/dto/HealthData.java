/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * Payload of {@code GET /v1/health}.
 *
 * @param status readiness, currently always {@code "ready"} while the server answers
 * @param hostType {@code "live"} or {@code "worker"}
 * @param qupathVersion QuPath version detected in the host process
 * @param bridgeVersion bridge implementation version
 * @param protocolVersion protocol version
 * @param javaVersion Java runtime version of the host process
 * @param fxThreadBlocked live hosts: whether the JavaFX thread missed its deadline; workers: null
 */
public record HealthData(
        @SerializedName("status") String status,
        @SerializedName("host_type") String hostType,
        @SerializedName("qupath_version") String qupathVersion,
        @SerializedName("bridge_version") String bridgeVersion,
        @SerializedName("protocol_version") String protocolVersion,
        @SerializedName("java_version") String javaVersion,
        @SerializedName("fx_thread_blocked") Boolean fxThreadBlocked) {

    /**
     * Creates health data.
     *
     * @param status status
     * @param hostType host type
     * @param qupathVersion QuPath version
     * @param bridgeVersion bridge version
     * @param protocolVersion protocol version
     * @param javaVersion Java version
     * @param fxThreadBlocked FX state or null
     */
    public HealthData {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(hostType, "hostType");
        Objects.requireNonNull(qupathVersion, "qupathVersion");
        Objects.requireNonNull(bridgeVersion, "bridgeVersion");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(javaVersion, "javaVersion");
    }
}
