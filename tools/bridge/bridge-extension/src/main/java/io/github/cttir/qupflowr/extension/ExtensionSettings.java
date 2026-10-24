/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import io.github.cttir.qupflowr.core.bridge.RuntimeDirectories;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Start-up settings of the extension, read from system properties (set on the QuPath command line
 * or through {@code JAVA_TOOL_OPTIONS}).
 *
 * @param autostart {@code -Dqupflowr.autostart=true}: start the bridge while the extension installs
 * @param runtimeDir runtime directory ({@code -Dqupflowr.runtimeDir}, otherwise the per-user
 *     default of {@link RuntimeDirectories})
 * @param language UI language ({@code -Dqupflowr.language}, default {@code de})
 */
record ExtensionSettings(boolean autostart, Path runtimeDir, String language) {

    /** System property enabling autostart. */
    static final String PROPERTY_AUTOSTART = "qupflowr.autostart";

    ExtensionSettings {
        Objects.requireNonNull(runtimeDir, "runtimeDir");
        Objects.requireNonNull(language, "language");
    }

    /**
     * Reads the settings of this process.
     *
     * @return settings
     */
    static ExtensionSettings fromSystem() {
        return from(System.getenv(), System::getProperty);
    }

    /**
     * Reads settings from explicit sources.
     *
     * @param environment environment variables
     * @param properties system property lookup
     * @return settings
     */
    static ExtensionSettings from(
            Map<String, String> environment, UnaryOperator<String> properties) {
        boolean autostart =
                "true"
                        .equalsIgnoreCase(
                                String.valueOf(properties.apply(PROPERTY_AUTOSTART)).strip());
        Path runtimeDir = RuntimeDirectories.resolve(environment, properties);
        String language =
                Messages.forLanguage(properties.apply(Messages.PROPERTY_LANGUAGE)).language();
        return new ExtensionSettings(autostart, runtimeDir, language);
    }
}
