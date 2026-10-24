/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.qupath;

import java.util.Objects;

/**
 * Result of comparing the detected QuPath version with the version this bridge supports.
 *
 * @param required version the adapter supports
 * @param detected detected version, or null if unknown
 * @param status comparison outcome
 */
public record VersionCheck(String required, String detected, Status status) {

    /** Comparison outcome. */
    public enum Status {
        /** The detected version is exactly the supported one. */
        SUPPORTED,
        /** A different version was detected. */
        UNSUPPORTED,
        /** No version could be detected. */
        UNKNOWN
    }

    /**
     * Validates the result.
     *
     * @param required required version
     * @param detected detected version or null
     * @param status status
     */
    public VersionCheck {
        Objects.requireNonNull(required, "required");
        Objects.requireNonNull(status, "status");
        if ((status == Status.UNKNOWN) != (detected == null)) {
            throw new IllegalArgumentException(
                    "Detected version must be null exactly when unknown");
        }
    }

    /**
     * Tells whether the bridge may start.
     *
     * @return true for {@link Status#SUPPORTED}
     */
    public boolean supported() {
        return status == Status.SUPPORTED;
    }

    /**
     * Returns a machine-readable reason for an unsupported result.
     *
     * @return {@code qupath_version_unsupported}, {@code qupath_version_unknown} or null when
     *     supported
     */
    public String reason() {
        return switch (status) {
            case SUPPORTED -> null;
            case UNSUPPORTED -> "qupath_version_unsupported";
            case UNKNOWN -> "qupath_version_unknown";
        };
    }
}
