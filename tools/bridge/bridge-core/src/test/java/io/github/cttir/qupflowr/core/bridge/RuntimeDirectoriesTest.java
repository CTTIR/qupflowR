/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class RuntimeDirectoriesTest {

    @Test
    void propertyWinsOverEverything() {
        Map<String, String> env =
                Map.of("QUPFLOWR_RUNTIME_DIR", "/env/dir", "XDG_RUNTIME_DIR", "/run/user/1000");
        Map<String, String> props = linux("/home/u");
        props.put("qupflowr.runtimeDir", "/prop/dir/../dir2");
        assertEquals(Path.of("/prop/dir2"), RuntimeDirectories.resolve(env, props::get));
    }

    @Test
    void environmentVariableComesSecond() {
        Map<String, String> env =
                Map.of("QUPFLOWR_RUNTIME_DIR", "/env/dir", "XDG_RUNTIME_DIR", "/run/user/1000");
        assertEquals(Path.of("/env/dir"), RuntimeDirectories.resolve(env, linux("/home/u")::get));
    }

    @Test
    void linuxPrefersXdgRuntimeDirThenCacheThenHome() {
        assertEquals(
                Path.of("/run/user/1000/qupflowr"),
                RuntimeDirectories.resolve(
                        Map.of("XDG_RUNTIME_DIR", "/run/user/1000", "XDG_CACHE_HOME", "/c"),
                        linux("/home/u")::get));
        assertEquals(
                Path.of("/c/qupflowr/runtime"),
                RuntimeDirectories.resolve(
                        Map.of("XDG_RUNTIME_DIR", "relative/run", "XDG_CACHE_HOME", "/c"),
                        linux("/home/u")::get));
        assertEquals(
                Path.of("/home/u/.cache/qupflowr/runtime"),
                RuntimeDirectories.resolve(Map.of("XDG_CACHE_HOME", " "), linux("/home/u")::get));
    }

    @Test
    void macOsAndWindowsDefaults() {
        Map<String, String> mac = linux("/Users/u");
        mac.put("os.name", "Mac OS X");
        assertEquals(
                Path.of("/Users/u/Library/Caches/qupflowr/runtime"),
                RuntimeDirectories.resolve(Map.of("XDG_RUNTIME_DIR", "/ignored"), mac::get));
        Map<String, String> windows = linux("/h");
        windows.put("os.name", "Windows 11");
        assertEquals(
                Path.of("/l/qupflowr/runtime"),
                RuntimeDirectories.resolve(Map.of("LOCALAPPDATA", "/l"), windows::get));
        assertEquals(
                Path.of("/h/AppData/Local/qupflowr/runtime"),
                RuntimeDirectories.resolve(Map.of(), windows::get));
    }

    @Test
    void failsWithoutUsableHome() {
        Map<String, String> props = linux("relative");
        assertThrows(
                IllegalStateException.class,
                () -> RuntimeDirectories.resolve(Map.of(), props::get));
    }

    private static Map<String, String> linux(String home) {
        Map<String, String> props = new HashMap<>();
        props.put("os.name", "Linux");
        props.put("user.home", home);
        return props;
    }
}
