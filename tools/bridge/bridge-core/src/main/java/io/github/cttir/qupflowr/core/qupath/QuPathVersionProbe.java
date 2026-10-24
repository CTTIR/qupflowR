/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.qupath;

import java.util.Optional;

/** Detects the QuPath version of the current process. */
@FunctionalInterface
public interface QuPathVersionProbe {

    /**
     * Detects the version.
     *
     * @return version string such as {@code 0.7.0}, or empty if QuPath is absent or reports none
     */
    Optional<String> detectVersion();
}
