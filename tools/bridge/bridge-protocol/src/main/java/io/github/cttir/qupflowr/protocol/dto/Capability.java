/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol.dto;

import com.google.gson.annotations.SerializedName;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One capability entry: a feature with its availability, the reason when unavailable, the host
 * modes it works in, the QuPath versions it was tested with and its limits.
 *
 * @param id dotted capability id, e.g. {@code bridge.health} or {@code objects.read}
 * @param available whether the host serves this capability now
 * @param reason short English explanation when unavailable, null when available
 * @param reasonCode machine-readable reason when unavailable (e.g. {@code not_implemented}), null
 *     when available
 * @param modes host modes ({@code live}, {@code worker}) in which it is available
 * @param testedVersions QuPath versions with passing evidence for this capability
 * @param limits numeric or textual limits (e.g. {@code max_request_body_bytes})
 */
public record Capability(
        @SerializedName("id") String id,
        @SerializedName("available") boolean available,
        @SerializedName("reason") String reason,
        @SerializedName("reason_code") String reasonCode,
        @SerializedName("modes") List<String> modes,
        @SerializedName("tested_versions") List<String> testedVersions,
        @SerializedName("limits") Map<String, Object> limits) {

    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z0-9_]+)*");
    private static final Pattern REASON_CODE = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Set<String> MODES = Set.of("live", "worker");

    /**
     * Creates and validates an entry: available entries have no reason, unavailable entries must
     * explain themselves with a reason text and code and list no modes.
     *
     * @param id id
     * @param available availability
     * @param reason reason text or null
     * @param reasonCode reason code or null
     * @param modes modes
     * @param testedVersions tested QuPath versions
     * @param limits limits
     */
    public Capability {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid capability id: " + id);
        }
        modes = List.copyOf(Objects.requireNonNull(modes, "modes"));
        testedVersions = List.copyOf(Objects.requireNonNull(testedVersions, "testedVersions"));
        limits =
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(Objects.requireNonNull(limits, "limits")));
        if (available && (reason != null || reasonCode != null)) {
            throw new IllegalArgumentException(
                    "Available capability " + id + " must not have a reason");
        }
        if (!available
                && (reason == null
                        || reason.isBlank()
                        || reasonCode == null
                        || !REASON_CODE.matcher(reasonCode).matches())) {
            throw new IllegalArgumentException(
                    "Unavailable capability " + id + " needs a reason and a code");
        }
        if (!available && !modes.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unavailable capability " + id + " must not list modes");
        }
        if (!MODES.containsAll(modes) || Set.copyOf(modes).size() != modes.size()) {
            throw new IllegalArgumentException(
                    "Capability " + id + " has unknown or duplicate modes");
        }
    }

    /**
     * Creates an available capability.
     *
     * @param id id
     * @param modes host modes
     * @param testedVersions tested QuPath versions
     * @param limits limits
     * @return the entry
     */
    public static Capability available(
            String id,
            List<String> modes,
            List<String> testedVersions,
            Map<String, Object> limits) {
        return new Capability(id, true, null, null, modes, testedVersions, limits);
    }

    /**
     * Creates an unavailable capability.
     *
     * @param id id
     * @param reason short English explanation
     * @param reasonCode reason code, e.g. {@code not_implemented}
     * @return the entry
     */
    public static Capability unavailable(String id, String reason, String reasonCode) {
        return new Capability(id, false, reason, reasonCode, List.of(), List.of(), Map.of());
    }
}
