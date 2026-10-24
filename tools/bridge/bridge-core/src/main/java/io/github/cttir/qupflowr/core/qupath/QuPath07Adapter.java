/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.qupath;

import java.util.Objects;
import java.util.Optional;

/**
 * Boundary for all QuPath-version-specific calls (docs/03). This build implements only the version
 * check; project, image, object and measurement access follow in later tasks.
 */
public final class QuPath07Adapter {

    /** The exact QuPath version this adapter was built and tested against. */
    public static final String REQUIRED_VERSION = "0.7.0";

    private final QuPathVersionProbe probe;

    /**
     * Creates the adapter.
     *
     * @param probe version probe of the current process
     */
    public QuPath07Adapter(QuPathVersionProbe probe) {
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    /**
     * Compares the detected version with {@link #REQUIRED_VERSION}. The comparison is exact: other
     * patch releases and pre-releases are unsupported until they have their own evidence.
     *
     * @return comparison result
     */
    public VersionCheck checkVersion() {
        Optional<String> detected =
                probe.detectVersion().map(String::strip).filter(v -> !v.isEmpty());
        if (detected.isEmpty()) {
            return new VersionCheck(REQUIRED_VERSION, null, VersionCheck.Status.UNKNOWN);
        }
        String version = detected.get();
        String normalised = version.startsWith("v") ? version.substring(1) : version;
        VersionCheck.Status status =
                REQUIRED_VERSION.equals(normalised)
                        ? VersionCheck.Status.SUPPORTED
                        : VersionCheck.Status.UNSUPPORTED;
        return new VersionCheck(REQUIRED_VERSION, version, status);
    }
}
