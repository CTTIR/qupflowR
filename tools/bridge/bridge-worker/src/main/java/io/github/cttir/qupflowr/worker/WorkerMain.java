/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.worker;

import io.github.cttir.qupflowr.core.bridge.BridgeHostInfo;
import io.github.cttir.qupflowr.core.bridge.BridgeServer;
import io.github.cttir.qupflowr.core.bridge.RuntimeReport;
import io.github.cttir.qupflowr.core.qupath.GeneralToolsVersionProbe;
import io.github.cttir.qupflowr.core.qupath.QuPath07Adapter;
import io.github.cttir.qupflowr.core.qupath.QuPathVersionProbe;
import io.github.cttir.qupflowr.core.qupath.VersionCheck;
import io.github.cttir.qupflowr.protocol.BuildInfo;
import io.github.cttir.qupflowr.protocol.HostType;
import io.github.cttir.qupflowr.protocol.ProtocolVersion;
import io.github.cttir.qupflowr.protocol.json.ProtocolJson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Headless worker host serving the bridge protocol with {@code host_type "worker"}.
 *
 * <p>Ownership: the parent keeps the worker's standard input open. End of file (parent exited or
 * closed the pipe) stops the bridge, removes the discovery files and returns {@value #EXIT_OK}.
 * SIGTERM runs the same cleanup in a shutdown hook (the JVM then exits with 143).
 *
 * <p>Standard output carries one JSON line per event and nothing else from this class: {@code
 * {"event":"ready","instance_id":...,"manifest":...}} once the manifest is published, or {@code
 * {"event":"failed","reason":...}} if the worker cannot start. The token is never printed.
 *
 * <p>Usage: {@code WorkerMain --runtime-dir <dir>} (or {@code -Dqupflowr.runtimeDir}), {@code
 * WorkerMain --version}, {@code WorkerMain --help}. {@link #run(String[])} is the entry point for
 * launchers that own the JVM (the QuPath script bootstrap) and never calls {@link System#exit}.
 */
public final class WorkerMain {

    /** Clean stop after standard input closed. */
    public static final int EXIT_OK = 0;

    /** The bridge could not start. */
    public static final int EXIT_START_FAILED = 1;

    /** Invalid command line. */
    public static final int EXIT_USAGE = 2;

    /** The QuPath version on the class path is not supported. */
    public static final int EXIT_UNSUPPORTED_QUPATH = 3;

    private static final Logger logger = LoggerFactory.getLogger(WorkerMain.class);

    private WorkerMain() {}

    /**
     * Command-line entry point; exits the JVM with the status of {@link #run(String[])}.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        System.exit(run(args));
    }

    /**
     * Runs the worker with the process streams and QuPath's version probe, blocking until standard
     * input reaches end of file.
     *
     * @param args command-line arguments
     * @return exit status
     */
    public static int run(String[] args) {
        return run(args, System.in, System.out, System.err, new GeneralToolsVersionProbe());
    }

    static int run(
            String[] args,
            InputStream owner,
            PrintStream out,
            PrintStream err,
            QuPathVersionProbe probe) {
        Path runtimeDir = null;
        String configured = System.getProperty("qupflowr.runtimeDir");
        if (configured != null && !configured.isBlank()) {
            runtimeDir = Path.of(configured);
        }
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--runtime-dir" -> {
                    if (i + 1 >= args.length || args[i + 1].isBlank()) {
                        return usage(err, "--runtime-dir needs a value");
                    }
                    runtimeDir = Path.of(args[++i]);
                }
                case "--version" -> {
                    return version(out, probe);
                }
                case "--help" -> {
                    printUsage(out);
                    return EXIT_OK;
                }
                default -> {
                    return usage(err, "unknown argument");
                }
            }
        }
        if (runtimeDir == null) {
            return usage(err, "runtime directory missing");
        }

        VersionCheck check = new QuPath07Adapter(probe).checkVersion();
        if (!check.supported()) {
            Map<String, Object> failed = event("failed");
            failed.put("reason", check.reason());
            failed.put("qupath_version", check.detected());
            failed.put("required_qupath_version", check.required());
            emit(out, failed);
            err.println(
                    "qupflowR worker: QuPath "
                            + check.detected()
                            + " is not supported (requires "
                            + check.required()
                            + ")");
            return EXIT_UNSUPPORTED_QUPATH;
        }

        long startNanos = System.nanoTime();
        logger.info("qupflowR worker runtime: {}", RuntimeReport.describe());
        BridgeServer server;
        try {
            server =
                    BridgeServer.start(
                            new BridgeHostInfo(
                                    runtimeDir.toAbsolutePath().normalize(),
                                    HostType.WORKER,
                                    check.detected(),
                                    BuildInfo.current().bridgeVersion(),
                                    null));
        } catch (IOException | RuntimeException e) {
            Map<String, Object> failed = event("failed");
            failed.put("reason", "bridge_start_failed");
            emit(out, failed);
            err.println(
                    "qupflowR worker: bridge could not start: "
                            + e.getClass().getSimpleName()
                            + ": "
                            + e.getMessage());
            return EXIT_START_FAILED;
        }
        Thread hook =
                new Thread(
                        () -> {
                            err.println(
                                    "qupflowR worker shutdown hook: stopping bridge instance "
                                            + server.instanceId());
                            server.stop();
                        },
                        "qupflowr-worker-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);

        Map<String, Object> ready = event("ready");
        ready.put("instance_id", server.instanceId());
        ready.put("manifest", server.manifestPath().toString());
        emit(out, ready);
        logger.info(
                "qupflowR worker ready in {} ms (instance {}, port {})",
                (System.nanoTime() - startNanos) / 1_000_000,
                server.instanceId(),
                server.port());

        awaitEndOfInput(owner);
        logger.info(
                "qupflowR worker: standard input closed, stopping instance {}",
                server.instanceId());
        server.stop();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // the JVM is already shutting down
        }
        return EXIT_OK;
    }

    private static void awaitEndOfInput(InputStream owner) {
        CountDownLatch ownerGone = new CountDownLatch(1);
        Thread watcher =
                new Thread(
                        () -> {
                            byte[] buffer = new byte[256];
                            try {
                                while (owner.read(buffer) >= 0) {
                                    // content is ignored; only end of file matters
                                }
                            } catch (IOException e) {
                                // a broken pipe also means the owner is gone
                            }
                            ownerGone.countDown();
                        },
                        "qupflowr-worker-owner");
        watcher.setDaemon(true);
        watcher.start();
        try {
            ownerGone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int version(PrintStream out, QuPathVersionProbe probe) {
        VersionCheck check = new QuPath07Adapter(probe).checkVersion();
        BuildInfo build = BuildInfo.current();
        Map<String, Object> info = event("version");
        info.put("bridge_version", build.bridgeVersion());
        info.put("protocol_version", ProtocolVersion.CURRENT_STRING);
        info.put("required_qupath_version", check.required());
        info.put("qupath_version", check.detected());
        info.put("qupath_version_supported", check.supported());
        info.put("java_version", Runtime.version().toString());
        emit(out, info);
        return check.supported() ? EXIT_OK : EXIT_UNSUPPORTED_QUPATH;
    }

    private static Map<String, Object> event(String name) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("event", name);
        return map;
    }

    private static void emit(PrintStream out, Map<String, Object> event) {
        synchronized (out) {
            out.println(ProtocolJson.toJson(event));
            out.flush();
        }
    }

    private static int usage(PrintStream err, String message) {
        err.println("qupflowR worker: " + message);
        printUsage(err);
        return EXIT_USAGE;
    }

    private static void printUsage(PrintStream stream) {
        stream.println("usage: WorkerMain --runtime-dir <dir> | --version | --help");
    }
}
