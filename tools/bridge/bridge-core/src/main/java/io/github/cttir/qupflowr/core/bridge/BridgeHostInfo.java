/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.core.bridge;

import io.github.cttir.qupflowr.protocol.HostType;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Host-specific values of a bridge instance. Keeps the core free of JavaFX and QuPath GUI types so
 * the extension and the worker share the same server.
 *
 * @param runtimeDir base runtime directory for discovery files
 * @param hostType live extension or headless worker
 * @param qupathVersion QuPath version detected in this process
 * @param bridgeVersion bridge implementation version
 * @param fxThreadBlocked live hosts: probe whether the JavaFX thread missed its deadline; workers:
 *     null
 */
public record BridgeHostInfo(
        Path runtimeDir,
        HostType hostType,
        String qupathVersion,
        String bridgeVersion,
        BooleanSupplier fxThreadBlocked) {

    /**
     * Validates the host information.
     *
     * @param runtimeDir runtime directory
     * @param hostType host type
     * @param qupathVersion QuPath version
     * @param bridgeVersion bridge version
     * @param fxThreadBlocked FX probe (required for live hosts, null for workers)
     */
    public BridgeHostInfo {
        Objects.requireNonNull(runtimeDir, "runtimeDir");
        Objects.requireNonNull(hostType, "hostType");
        Objects.requireNonNull(qupathVersion, "qupathVersion");
        Objects.requireNonNull(bridgeVersion, "bridgeVersion");
        if ((hostType == HostType.LIVE) != (fxThreadBlocked != null)) {
            throw new IllegalArgumentException(
                    "Live hosts need an FX probe; workers must not have one");
        }
    }
}
