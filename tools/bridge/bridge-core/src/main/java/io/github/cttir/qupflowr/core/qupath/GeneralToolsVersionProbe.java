/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.qupath;

import qupath.lib.common.GeneralTools;

import java.util.Optional;

/**
 * Reads the version of the QuPath core module on the class path with {@code
 * qupath.lib.common.GeneralTools#getVersion()} (QuPath 0.7.0: {@code
 * qupath-core/src/main/java/qupath/lib/common/GeneralTools.java}, returns the {@code
 * Implementation-Version} of the core jar or null when unknown).
 */
public final class GeneralToolsVersionProbe implements QuPathVersionProbe {

    @Override
    public Optional<String> detectVersion() {
        try {
            String version = GeneralTools.getVersion();
            return version == null || version.isBlank()
                    ? Optional.empty()
                    : Optional.of(version.strip());
        } catch (LinkageError e) {
            // qupath-core is not on the class path
            return Optional.empty();
        }
    }
}
