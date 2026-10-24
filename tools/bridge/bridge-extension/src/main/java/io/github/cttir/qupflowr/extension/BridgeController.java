/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.extension;

import io.github.cttir.qupflowr.core.bridge.BridgeHostInfo;
import io.github.cttir.qupflowr.core.bridge.BridgeServer;
import io.github.cttir.qupflowr.core.qupath.QuPath07Adapter;
import io.github.cttir.qupflowr.core.qupath.VersionCheck;
import io.github.cttir.qupflowr.protocol.HostType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Lifecycle of the live bridge inside QuPath.
 *
 * <p>Start and stop run on one dedicated background thread, never on the JavaFX application thread:
 * binding, file I/O and closing connections may block. State changes are handed to a publisher (the
 * extension forwards them to the FX thread). A JVM shutdown hook removes the discovery files if
 * QuPath exits while the bridge runs.
 */
final class BridgeController {

    private static final Logger logger = LoggerFactory.getLogger(BridgeController.class);

    /** Bridge life-cycle phase. */
    enum Phase {
        /** Not running. */
        STOPPED,
        /** Start requested. */
        STARTING,
        /** Serving requests. */
        RUNNING,
        /** The last start failed. */
        FAILED,
        /** The QuPath version is not supported; the bridge refuses to start. */
        UNSUPPORTED
    }

    /**
     * Published state.
     *
     * @param phase phase
     * @param text localised status text
     * @param port port while running, otherwise 0
     */
    record State(Phase phase, String text, int port) {}

    /** Starts a bridge server; replaceable in tests. */
    @FunctionalInterface
    interface ServerStarter {
        /**
         * Starts a server.
         *
         * @param host host information
         * @return running server
         * @throws IOException if the server cannot start
         */
        BridgeServer start(BridgeHostInfo host) throws IOException;
    }

    private final QuPath07Adapter adapter;
    private final Path runtimeDir;
    private final String bridgeVersion;
    private final BooleanSupplier fxThreadBlocked;
    private final Messages messages;
    private final Consumer<State> publisher;
    private final ServerStarter starter;
    private final ExecutorService lifecycle =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "qupflowr-bridge-lifecycle");
                        t.setDaemon(true);
                        return t;
                    });
    private final Thread shutdownHook =
            new Thread(this::stopFromShutdownHook, "qupflowr-bridge-shutdown");

    private volatile BridgeServer server;
    private volatile State state;
    private boolean shutdownHookInstalled;

    BridgeController(
            QuPath07Adapter adapter,
            Path runtimeDir,
            String bridgeVersion,
            BooleanSupplier fxThreadBlocked,
            Messages messages,
            Consumer<State> publisher,
            ServerStarter starter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.runtimeDir = Objects.requireNonNull(runtimeDir, "runtimeDir");
        this.bridgeVersion = Objects.requireNonNull(bridgeVersion, "bridgeVersion");
        this.fxThreadBlocked = Objects.requireNonNull(fxThreadBlocked, "fxThreadBlocked");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.starter = Objects.requireNonNull(starter, "starter");
        this.state = new State(Phase.STOPPED, messages.get("status.stopped"), 0);
    }

    /**
     * Requests a start on the lifecycle thread.
     *
     * @param origin who asked (for the log), e.g. {@code menu} or {@code autostart}
     * @return future completing after the attempt
     */
    Future<?> startAsync(String origin) {
        return lifecycle.submit(() -> start(origin));
    }

    /**
     * Requests a stop on the lifecycle thread.
     *
     * @param origin who asked (for the log)
     * @return future completing after the stop
     */
    Future<?> stopAsync(String origin) {
        return lifecycle.submit(() -> stop(origin));
    }

    /**
     * Returns the current state.
     *
     * @return last published state
     */
    State state() {
        return state;
    }

    /**
     * Returns the running server.
     *
     * @return server or null
     */
    BridgeServer currentServer() {
        return server;
    }

    /**
     * Returns the shutdown hook (tests run it directly).
     *
     * @return hook thread
     */
    Thread shutdownHook() {
        return shutdownHook;
    }

    private synchronized void start(String origin) {
        BridgeServer current = server;
        if (current != null && current.isRunning()) {
            logger.info(
                    "qupflowR bridge already running (instance {}); start request from {} ignored",
                    current.instanceId(),
                    origin);
            return;
        }
        VersionCheck check = adapter.checkVersion();
        if (!check.supported()) {
            String detected = check.detected() == null ? "?" : check.detected();
            logger.error(
                    "qupflowR bridge not started ({}): {} detected, {} required",
                    check.reason(),
                    detected,
                    check.required());
            publish(
                    new State(
                            Phase.UNSUPPORTED,
                            messages.get("status.unsupported", detected, check.required()),
                            0));
            return;
        }
        logger.info(
                "qupflowR bridge start requested ({}) on thread '{}'",
                origin,
                Thread.currentThread().getName());
        publish(new State(Phase.STARTING, messages.get("status.starting"), 0));
        installShutdownHook();
        try {
            BridgeServer started =
                    starter.start(
                            new BridgeHostInfo(
                                    runtimeDir,
                                    HostType.LIVE,
                                    check.detected(),
                                    bridgeVersion,
                                    fxThreadBlocked));
            server = started;
            publish(
                    new State(
                            Phase.RUNNING,
                            messages.get("status.running", started.port()),
                            started.port()));
        } catch (IOException | RuntimeException e) {
            server = null;
            logger.error(
                    "qupflowR bridge failed to start: {}: {}",
                    e.getClass().getSimpleName(),
                    e.getMessage());
            publish(new State(Phase.FAILED, messages.get("status.failed"), 0));
        }
    }

    private synchronized void stop(String origin) {
        BridgeServer current = server;
        if (current == null) {
            logger.info("qupflowR bridge not running; stop request from {} ignored", origin);
            return;
        }
        logger.info("qupflowR bridge stop requested ({})", origin);
        current.stop();
        server = null;
        publish(new State(Phase.STOPPED, messages.get("status.stopped"), 0));
    }

    private void installShutdownHook() {
        if (!shutdownHookInstalled) {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            shutdownHookInstalled = true;
        }
    }

    private void stopFromShutdownHook() {
        BridgeServer current = server;
        if (current != null) {
            // Logging may already be shut down during JVM exit; stderr is reliable.
            System.err.println(
                    "qupflowR shutdown hook: stopping bridge instance " + current.instanceId());
            current.stop();
            System.err.println(
                    "qupflowR shutdown hook: discovery files removed for instance "
                            + current.instanceId());
        }
    }

    private void publish(State next) {
        state = next;
        try {
            publisher.accept(next);
        } catch (RuntimeException e) {
            logger.warn("qupflowR state publisher failed: {}", e.getClass().getSimpleName());
        }
    }
}
