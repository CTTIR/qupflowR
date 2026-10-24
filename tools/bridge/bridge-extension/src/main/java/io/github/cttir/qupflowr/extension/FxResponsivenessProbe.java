/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Health probe of the JavaFX application thread: posts one runnable and waits on the calling (HTTP)
 * thread, never on the FX thread, for at most {@link #DEADLINE_MILLIS}.
 */
final class FxResponsivenessProbe {

    /** Deadline after which the FX thread counts as blocked. */
    static final long DEADLINE_MILLIS = 500;

    /**
     * Checks the FX thread.
     *
     * @return true if the FX thread did not run a posted runnable within the deadline
     */
    boolean isBlocked() {
        if (Platform.isFxApplicationThread()) {
            return false;
        }
        CountDownLatch latch = new CountDownLatch(1);
        try {
            Platform.runLater(latch::countDown);
        } catch (IllegalStateException e) {
            return true; // toolkit not running
        }
        try {
            return !latch.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
