/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One-line description of the Java runtime of a host, logged at start. Contains selected system
 * properties only: no environment variables and no secrets.
 */
public final class RuntimeReport {

    private static final String[] PROPERTIES = {
        "java.version",
        "java.runtime.version",
        "java.vendor",
        "java.home",
        "java.io.tmpdir",
        "user.home",
        "java.util.prefs.userRoot",
        "java.awt.headless",
        "prism.order",
        "jpackage.app-path",
        "qupflowr.autostart",
        "qupflowr.runtimeDir"
    };

    private RuntimeReport() {}

    /**
     * Collects the report entries.
     *
     * @return ordered key/value pairs
     */
    public static Map<String, String> collect() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : PROPERTIES) {
            map.put(key, System.getProperty(key));
        }
        map.put(
                "module.jdk.httpserver.present",
                String.valueOf(ModuleLayer.boot().findModule("jdk.httpserver").isPresent()));
        map.put("pid", String.valueOf(ProcessHandle.current().pid()));
        return map;
    }

    /**
     * Formats the report.
     *
     * @return {@code key=value} pairs separated by {@code " | "}
     */
    public static String describe() {
        return collect().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" | "));
    }
}
