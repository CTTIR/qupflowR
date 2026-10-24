/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.qupath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Optional;

/** Version gate of the adapter; runs without QuPath on the class path. */
class QuPath07AdapterTest {

    @Test
    void exactVersionIsSupported() {
        VersionCheck check = new QuPath07Adapter(() -> Optional.of("0.7.0")).checkVersion();
        assertTrue(check.supported());
        assertEquals("0.7.0", check.required());
        assertEquals("0.7.0", check.detected());
        assertNull(check.reason());
        assertTrue(new QuPath07Adapter(() -> Optional.of(" v0.7.0\n")).checkVersion().supported());
    }

    @Test
    void otherVersionsAreUnsupported() {
        for (String version :
                new String[] {"0.7.1", "0.6.0", "0.7.0-rc1", "0.7", "1.0.0", "0.70"}) {
            VersionCheck check = new QuPath07Adapter(() -> Optional.of(version)).checkVersion();
            assertFalse(check.supported(), version);
            assertEquals(VersionCheck.Status.UNSUPPORTED, check.status());
            assertEquals("qupath_version_unsupported", check.reason());
            assertEquals(version, check.detected());
        }
    }

    @Test
    void missingVersionIsUnknown() {
        for (Optional<String> probe :
                java.util.List.of(Optional.<String>empty(), Optional.of("  "))) {
            VersionCheck check = new QuPath07Adapter(() -> probe).checkVersion();
            assertEquals(VersionCheck.Status.UNKNOWN, check.status());
            assertEquals("qupath_version_unknown", check.reason());
            assertNull(check.detected());
        }
    }

    @Test
    void generalToolsProbeIsAbsentWithoutQuPath() {
        // bridge-core tests run without qupath-core: the probe must report "unknown", not fail.
        assertThrows(
                ClassNotFoundException.class,
                () -> Class.forName("qupath.lib.common.GeneralTools"));
        assertEquals(Optional.empty(), new GeneralToolsVersionProbe().detectVersion());
    }

    @Test
    void versionCheckInvariants() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new VersionCheck("0.7.0", null, VersionCheck.Status.SUPPORTED));
        assertThrows(
                IllegalArgumentException.class,
                () -> new VersionCheck("0.7.0", "0.7.0", VersionCheck.Status.UNKNOWN));
    }
}
