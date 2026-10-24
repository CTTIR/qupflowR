/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import io.github.cttir.qupflowr.core.http.Limits;
import io.github.cttir.qupflowr.core.qupath.QuPath07Adapter;
import io.github.cttir.qupflowr.protocol.HostType;
import io.github.cttir.qupflowr.protocol.dto.CapabilitiesData;
import io.github.cttir.qupflowr.protocol.dto.Capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Capability report of this bridge build. Only what is implemented and tested is available; every
 * other feature of the protocol contract is listed as unavailable with reason code {@value
 * #NOT_IMPLEMENTED}, so clients can disable it with a cause instead of guessing.
 */
public final class Capabilities {

    /** Reason code for contract features that this build does not implement yet. */
    public static final String NOT_IMPLEMENTED = "not_implemented";

    /** Reason text accompanying {@link #NOT_IMPLEMENTED}. */
    public static final String NOT_IMPLEMENTED_TEXT = "Not implemented in this bridge build";

    /** Capabilities served by this build. */
    public static final List<String> AVAILABLE = List.of("bridge.health", "bridge.capabilities");

    /** Protocol v1 features (docs/06 endpoint table) not implemented in this build. */
    public static final List<String> NOT_YET_IMPLEMENTED =
            List.of(
                    "sessions.open",
                    "sessions.close",
                    "projects.create",
                    "images.list",
                    "images.read",
                    "classes.read",
                    "objects.read",
                    "measurements.read",
                    "measurements.dynamic",
                    "patches.write",
                    "project.save",
                    "viewer.context",
                    "viewer.select",
                    "viewer.viewport",
                    "events.poll",
                    "recipes.list",
                    "jobs.run",
                    "pixels.region",
                    "exports.run",
                    "projects.compare",
                    "scripts.run",
                    "tiles.read",
                    "artifacts.read",
                    "operations.lookup");

    private Capabilities() {}

    /**
     * Builds the capability report for a host.
     *
     * @param hostType host type
     * @param limits transport limits of the running server
     * @return report with available entries first, then unavailable ones
     */
    public static CapabilitiesData report(HostType hostType, Limits limits) {
        List<Capability> entries = new ArrayList<>();
        List<String> modes = List.of(HostType.LIVE.wireValue(), HostType.WORKER.wireValue());
        List<String> tested = List.of(QuPath07Adapter.REQUIRED_VERSION);
        for (String id : AVAILABLE) {
            entries.add(Capability.available(id, modes, tested, transportLimits(limits)));
        }
        for (String id : NOT_YET_IMPLEMENTED) {
            entries.add(Capability.unavailable(id, NOT_IMPLEMENTED_TEXT, NOT_IMPLEMENTED));
        }
        return new CapabilitiesData(hostType.wireValue(), entries);
    }

    private static Map<String, Object> transportLimits(Limits limits) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("max_request_body_bytes", 0L);
        map.put("max_connections", (long) limits.maxConnections());
        map.put("max_request_line_bytes", (long) limits.maxRequestLineBytes());
        map.put("max_header_block_bytes", (long) limits.maxHeaderBlockBytes());
        map.put("max_header_count", (long) limits.maxHeaderCount());
        map.put("idle_timeout_ms", limits.idleReadTimeout().toMillis());
        map.put("header_deadline_ms", limits.headerDeadline().toMillis());
        map.put("request_deadline_ms", limits.requestDeadline().toMillis());
        return map;
    }
}
