/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HostTypeTest {

    @Test
    void wireValuesFollowTheProtocol() {
        assertEquals("live", HostType.LIVE.wireValue());
        assertEquals("worker", HostType.WORKER.wireValue());
    }
}
