/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProtocolVersionTest {

    @Test
    void currentVersionMatchesTheBuildDeclaration() {
        // Independent source: gradle.properties (qupflowr.protocolVersion), passed by the build.
        assertEquals(
                System.getProperty("qupflowr.test.protocolVersion"),
                ProtocolVersion.CURRENT_STRING);
        assertEquals(new ProtocolVersion(1, 0), ProtocolVersion.CURRENT);
        assertEquals("/v1", ProtocolVersion.PATH_PREFIX);
    }

    @Test
    void parsesStrictMajorMinor() {
        assertEquals(new ProtocolVersion(1, 0), ProtocolVersion.parse("1.0"));
        assertEquals(new ProtocolVersion(0, 12), ProtocolVersion.parse("0.12"));
        assertEquals("3.4", ProtocolVersion.parse("3.4").toString());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "", "1", "1.", ".1", "01.0", "1.00", "1.0.0", "-1.0", "+1.0", "1.a", " 1.0", "1.0 ",
                "１.0", "12345.0", "1..0"
            })
    void rejectsInvalidVersions(String text) {
        assertThrows(IllegalArgumentException.class, () -> ProtocolVersion.parse(text));
        assertFalse(ProtocolVersion.isCompatible(text));
    }

    @Test
    void compatibilityDependsOnMajorOnly() {
        assertTrue(ProtocolVersion.isCompatible("1.0"));
        assertTrue(ProtocolVersion.isCompatible("1.7"));
        assertFalse(ProtocolVersion.isCompatible("2.0"));
        assertFalse(ProtocolVersion.isCompatible("0.9"));
        assertFalse(ProtocolVersion.isCompatible(null));
    }

    @Test
    void rejectsNegativeComponents() {
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolVersion(1, -1));
    }
}
