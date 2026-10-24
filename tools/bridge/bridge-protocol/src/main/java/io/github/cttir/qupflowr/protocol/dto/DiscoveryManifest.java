/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

/**
 * Discovery manifest {@code <runtime dir>/instances/<instance_id>.json}. It never contains the
 * token itself, only the path of the private token file.
 *
 * @param hostType {@code "live"} or {@code "worker"}
 * @param pid process id of the host JVM
 * @param processStartTime process start instant (ISO-8601, UTC) or null if the OS does not report
 *     it
 * @param instanceId bridge instance UUID
 * @param host listening address, always {@code 127.0.0.1}
 * @param port listening port
 * @param qupathVersion QuPath version detected in the host
 * @param bridgeVersion bridge implementation version
 * @param protocolVersion protocol version
 * @param tokenFile absolute path of the token file (0600)
 * @param createdAt creation instant of the manifest (ISO-8601, UTC)
 */
public record DiscoveryManifest(
        @SerializedName("host_type") String hostType,
        @SerializedName("pid") long pid,
        @SerializedName("process_start_time") String processStartTime,
        @SerializedName("instance_id") String instanceId,
        @SerializedName("host") String host,
        @SerializedName("port") int port,
        @SerializedName("qupath_version") String qupathVersion,
        @SerializedName("bridge_version") String bridgeVersion,
        @SerializedName("protocol_version") String protocolVersion,
        @SerializedName("token_file") String tokenFile,
        @SerializedName("created_at") String createdAt) {

    /**
     * Creates a manifest.
     *
     * @param hostType host type
     * @param pid pid
     * @param processStartTime start time or null
     * @param instanceId instance id
     * @param host host address
     * @param port port
     * @param qupathVersion QuPath version
     * @param bridgeVersion bridge version
     * @param protocolVersion protocol version
     * @param tokenFile token file path
     * @param createdAt creation time
     */
    public DiscoveryManifest {
        Objects.requireNonNull(hostType, "hostType");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(qupathVersion, "qupathVersion");
        Objects.requireNonNull(bridgeVersion, "bridgeVersion");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        Objects.requireNonNull(tokenFile, "tokenFile");
        Objects.requireNonNull(createdAt, "createdAt");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port " + port);
        }
    }
}
