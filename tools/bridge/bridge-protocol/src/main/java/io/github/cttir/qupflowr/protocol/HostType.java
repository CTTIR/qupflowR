/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.protocol;

import java.util.Locale;

/** Kind of process that serves the bridge protocol. */
public enum HostType {

    /** QuPath desktop application with the qupflowR extension. */
    LIVE,

    /** Headless worker process started by R. */
    WORKER;

    /**
     * Returns the protocol value ({@code "live"} or {@code "worker"}).
     *
     * @return wire value
     */
    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
