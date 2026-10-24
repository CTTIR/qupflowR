/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildInfoTest {

    @Test
    void generatedResourceCarriesTheBuildVersions() {
        BuildInfo info = BuildInfo.current();
        // Expected values come from gradle.properties and the QuPath version catalogue via the
        // build.
        assertEquals(System.getProperty("qupflowr.test.bridgeVersion"), info.bridgeVersion());
        assertEquals(System.getProperty("qupflowr.test.protocolVersion"), info.protocolVersion());
        assertEquals("0.7.0", info.qupathVersion());
        assertEquals(ProtocolVersion.CURRENT_STRING, info.protocolVersion());
    }
}
