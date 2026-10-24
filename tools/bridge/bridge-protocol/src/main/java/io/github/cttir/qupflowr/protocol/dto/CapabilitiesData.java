/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Payload of {@code GET /v1/capabilities}.
 *
 * @param hostType {@code "live"} or {@code "worker"}
 * @param capabilities entries with unique ids, in a stable order
 */
public record CapabilitiesData(
        @SerializedName("host_type") String hostType,
        @SerializedName("capabilities") List<Capability> capabilities) {

    /**
     * Creates the payload.
     *
     * @param hostType host type
     * @param capabilities entries
     * @throws IllegalArgumentException if two entries share an id
     */
    public CapabilitiesData {
        Objects.requireNonNull(hostType, "hostType");
        capabilities = List.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        Set<String> ids = new HashSet<>();
        for (Capability capability : capabilities) {
            if (!ids.add(capability.id())) {
                throw new IllegalArgumentException("Duplicate capability id " + capability.id());
            }
        }
    }
}
