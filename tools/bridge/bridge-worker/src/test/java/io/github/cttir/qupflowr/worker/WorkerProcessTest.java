/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The built worker jar in a separate JVM with the real QuPath core module: the jar must be
 * self-contained (only third-party jars are added), report the QuPath version from {@code
 * GeneralTools}, keep standard output to event lines and clean up on end of input and SIGTERM.
 */
class WorkerProcessTest {

    @TempDir Path tmp;

    private ChildJvm child;

    @AfterEach
    void killChild() {
        if (child != null) {
            child.kill();
        }
    }

    private List<String> classpath() {
        List<String> cp = new ArrayList<>();
        cp.add(System.getProperty("qupflowr.test.workerJar"));
        cp.addAll(ChildJvm.thirdPartyClasspath());
        return cp;
    }

    @Test
    void versionComesFromTheQuPathCoreJar() throws Exception {
        child = ChildJvm.start(tmp, classpath(), List.of(WorkerMain.class.getName(), "--version"));
        assertEquals(0, child.awaitExit(30_000), "stderr: " + child.stderr());
        JsonObject version = JsonParser.parseString(child.stdout().get(0)).getAsJsonObject();
        // Expected from the Implementation-Version of qupath-core-0.7.0.jar, not from bridge code.
        assertEquals("0.7.0", version.get("qupath_version").getAsString());
        assertEquals(
                System.getProperty("qupflowr.test.bridgeVersion"),
                version.get("bridge_version").getAsString());
    }

    @Test
    void endOfInputStopsWithExitZeroAndRemovesFiles() throws Exception {
        Path runtime = tmp.resolve("runtime");
        child =
                ChildJvm.start(
                        tmp,
                        classpath(),
                        List.of(WorkerMain.class.getName(), "--runtime-dir", runtime.toString()));
        JsonObject ready = child.awaitEvent(30_000);
        assertEquals("ready", ready.get("event").getAsString());
        JsonObject manifest = readManifest(ready);
        assertEquals(child.process().pid(), manifest.get("pid").getAsLong());
        String token = Files.readString(Path.of(manifest.get("token_file").getAsString())).trim();
        String health =
                WorkerMainTest.httpGet(manifest.get("port").getAsInt(), "/v1/health", token);
        assertTrue(health.startsWith("HTTP/1.1 200 "), health);
        assertTrue(health.contains("\"qupath_version\":\"0.7.0\""), health);

        child.process().getOutputStream().close();
        assertEquals(0, child.awaitExit(15_000), "stderr: " + child.stderr());
        assertNoDiscoveryFiles(runtime);
        assertTrue(
                child.stdout().stream().allMatch(line -> line.startsWith("{\"event\":")),
                "stdout carries event lines only: " + child.stdout());
        assertFalse(String.join("\n", child.stdout()).contains(token));
        assertFalse(String.join("\n", child.stderr()).contains(token));
    }

    @Test
    void sigtermStopsWith143AndRemovesFiles() throws Exception {
        Path runtime = tmp.resolve("runtime");
        child =
                ChildJvm.start(
                        tmp,
                        classpath(),
                        List.of(WorkerMain.class.getName(), "--runtime-dir", runtime.toString()));
        readManifest(child.awaitEvent(30_000));
        // SIGTERM through the process handle: Process.destroy() would also close our end of the
        // pipes.
        assertTrue(child.process().toHandle().destroy());
        assertEquals(143, child.awaitExit(15_000), "stderr: " + child.stderr());
        assertNoDiscoveryFiles(runtime);
        assertTrue(
                child.stderr().stream()
                        .anyMatch(
                                l -> l.contains("qupflowR worker shutdown hook: stopping bridge")),
                child.stderr().toString());
    }

    static JsonObject readManifest(JsonObject ready) throws Exception {
        return JsonParser.parseString(
                        Files.readString(Path.of(ready.get("manifest").getAsString())))
                .getAsJsonObject();
    }

    static void assertNoDiscoveryFiles(Path runtime) throws Exception {
        try (Stream<Path> files = Files.list(runtime.resolve("instances"))) {
            assertEquals(List.of(), files.toList());
        }
    }
}
