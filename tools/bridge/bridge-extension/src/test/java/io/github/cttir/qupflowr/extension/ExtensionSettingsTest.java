/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

class ExtensionSettingsTest {

    @Test
    void readsSystemProperties() {
        Map<String, String> props =
                new HashMap<>(Map.of("os.name", "Linux", "user.home", "/home/u"));
        props.put("qupflowr.autostart", "TRUE");
        props.put("qupflowr.runtimeDir", "/tmp/rt");
        props.put("qupflowr.language", "en");
        ExtensionSettings settings = ExtensionSettings.from(Map.of(), props::get);
        assertTrue(settings.autostart());
        assertEquals(Path.of("/tmp/rt"), settings.runtimeDir());
        assertEquals("en", settings.language());
    }

    @Test
    void defaultsToNoAutostartGermanAndPerUserRuntimeDir() {
        Map<String, String> props = Map.of("os.name", "Linux", "user.home", "/home/u");
        ExtensionSettings settings =
                ExtensionSettings.from(Map.of("XDG_RUNTIME_DIR", "/run/user/7"), props::get);
        assertFalse(settings.autostart());
        assertEquals(Path.of("/run/user/7/qupflowr"), settings.runtimeDir());
        assertEquals("de", settings.language());
        Map<String, String> yes = new HashMap<>(props);
        yes.put("qupflowr.autostart", "yes");
        assertFalse(
                ExtensionSettings.from(Map.of(), yes::get).autostart(),
                "only 'true' enables autostart");
    }
}
