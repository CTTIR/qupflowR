/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Resolution of the per-user runtime directory that holds discovery manifests and token files.
 *
 * <ol>
 *   <li>System property {@value #PROPERTY}
 *   <li>Environment variable {@value #ENVIRONMENT}
 *   <li>Linux and other Unix systems: {@code $XDG_RUNTIME_DIR/qupflowr} when {@code
 *       XDG_RUNTIME_DIR} is an absolute path, otherwise {@code $XDG_CACHE_HOME/qupflowr/runtime} or
 *       {@code ~/.cache/qupflowr/runtime}
 *   <li>macOS: {@code ~/Library/Caches/qupflowr/runtime}
 *   <li>Windows: {@code %LOCALAPPDATA%\qupflowr\runtime}, or {@code
 *       ~\AppData\Local\qupflowr\runtime}
 * </ol>
 *
 * A shared temporary directory is never used, so another local user cannot pre-create the
 * directory. {@link BridgeRuntimeFiles} verifies ownership and permissions of whatever is resolved.
 */
public final class RuntimeDirectories {

    /** System property naming the runtime directory. */
    public static final String PROPERTY = "qupflowr.runtimeDir";

    /** Environment variable naming the runtime directory. */
    public static final String ENVIRONMENT = "QUPFLOWR_RUNTIME_DIR";

    private RuntimeDirectories() {}

    /**
     * Resolves the runtime directory for this process.
     *
     * @return absolute, normalised path (not created)
     */
    public static Path resolve() {
        return resolve(System.getenv(), System::getProperty);
    }

    /**
     * Resolves the runtime directory from explicit inputs.
     *
     * @param environment environment variables
     * @param properties system property lookup
     * @return absolute, normalised path (not created)
     * @throws IllegalStateException if no location can be derived
     */
    public static Path resolve(Map<String, String> environment, UnaryOperator<String> properties) {
        String configured = properties.apply(PROPERTY);
        if (isSet(configured)) {
            return absolute(Path.of(configured));
        }
        String fromEnvironment = environment.get(ENVIRONMENT);
        if (isSet(fromEnvironment)) {
            return absolute(Path.of(fromEnvironment));
        }
        String os = String.valueOf(properties.apply("os.name")).toLowerCase(Locale.ROOT);
        String home = properties.apply("user.home");
        if (os.startsWith("windows")) {
            String localAppData = environment.get("LOCALAPPDATA");
            if (isAbsolute(localAppData)) {
                return absolute(Path.of(localAppData, "qupflowr", "runtime"));
            }
            return absolute(Path.of(requireHome(home), "AppData", "Local", "qupflowr", "runtime"));
        }
        if (os.startsWith("mac")) {
            return absolute(Path.of(requireHome(home), "Library", "Caches", "qupflowr", "runtime"));
        }
        String xdgRuntime = environment.get("XDG_RUNTIME_DIR");
        if (isAbsolute(xdgRuntime)) {
            return absolute(Path.of(xdgRuntime, "qupflowr"));
        }
        String xdgCache = environment.get("XDG_CACHE_HOME");
        if (isAbsolute(xdgCache)) {
            return absolute(Path.of(xdgCache, "qupflowr", "runtime"));
        }
        return absolute(Path.of(requireHome(home), ".cache", "qupflowr", "runtime"));
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isAbsolute(String value) {
        return isSet(value) && Path.of(value).isAbsolute();
    }

    private static String requireHome(String home) {
        if (!isAbsolute(home)) {
            throw new IllegalStateException(
                    "Cannot derive a runtime directory: user.home is not absolute");
        }
        return home;
    }

    private static Path absolute(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
