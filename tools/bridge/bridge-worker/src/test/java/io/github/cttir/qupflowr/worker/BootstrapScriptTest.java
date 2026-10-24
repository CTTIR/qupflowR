/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * The Groovy bootstrap of the qupath-script profile, run by Groovy's own command-line runner in a
 * JVM whose class path holds only third-party jars (as in QuPath's script command): the worker
 * classes must come from the verified jar.
 */
class BootstrapScriptTest {

    @TempDir Path tmp;

    private ChildJvm child;

    @AfterEach
    void killChild() {
        if (child != null) {
            child.kill();
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private ChildJvm runBootstrap(String... scriptArgs) throws Exception {
        List<String> args =
                new java.util.ArrayList<>(
                        List.of(
                                "groovy.ui.GroovyMain",
                                System.getProperty("qupflowr.test.bootstrap")));
        args.addAll(List.of(scriptArgs));
        return ChildJvm.start(tmp, ChildJvm.thirdPartyClasspath(), args);
    }

    @Test
    void verifiedJarStartsTheWorker() throws Exception {
        Path jar = Path.of(System.getProperty("qupflowr.test.workerJar"));
        Path runtime = tmp.resolve("runtime");
        child = runBootstrap(jar.toString(), sha256(jar).toUpperCase(), runtime.toString());
        JsonObject ready = child.awaitEvent(60_000);
        assertEquals("ready", ready.get("event").getAsString());
        JsonObject manifest = WorkerProcessTest.readManifest(ready);
        String token = Files.readString(Path.of(manifest.get("token_file").getAsString())).trim();
        String health =
                WorkerMainTest.httpGet(manifest.get("port").getAsInt(), "/v1/health", token);
        assertTrue(health.startsWith("HTTP/1.1 200 "), health);
        child.process().getOutputStream().close();
        assertEquals(0, child.awaitExit(30_000), "stderr: " + child.stderr());
        WorkerProcessTest.assertNoDiscoveryFiles(runtime);
        assertTrue(child.stderr().stream().anyMatch(l -> l.contains("WorkerMain.run returned 0")));
    }

    @Test
    void digestMismatchIsRefusedBeforeLoadingAnything() throws Exception {
        Path jar = Path.of(System.getProperty("qupflowr.test.workerJar"));
        String digest = sha256(jar);
        String wrong = (digest.charAt(0) == '0' ? "1" : "0") + digest.substring(1);
        Path runtime = tmp.resolve("runtime");
        child = runBootstrap(jar.toString(), wrong, runtime.toString());
        assertNotEquals(0, child.awaitExit(60_000));
        assertTrue(
                String.join("\n", child.stderr()).contains("worker jar digest mismatch"),
                child.stderr().toString());
        assertFalse(Files.exists(runtime));
        assertTrue(child.stdout().stream().noneMatch(l -> l.startsWith("{\"event\":")));
    }

    @Test
    void wrongArgumentsAreRefused() throws Exception {
        Path jar = Path.of(System.getProperty("qupflowr.test.workerJar"));
        child = runBootstrap(jar.toString(), sha256(jar));
        assertNotEquals(0, child.awaitExit(60_000));
        assertTrue(
                String.join("\n", child.stderr()).contains("expected 3 arguments"),
                child.stderr().toString());
        child = runBootstrap(jar.toString(), "not-a-digest", tmp.resolve("r").toString());
        assertNotEquals(0, child.awaitExit(60_000));
        assertTrue(
                String.join("\n", child.stderr()).contains("hexadecimal SHA-256"),
                child.stderr().toString());
    }
}
