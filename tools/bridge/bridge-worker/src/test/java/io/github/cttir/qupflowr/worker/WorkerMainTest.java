/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** WorkerMain in-process with an injected QuPath version and a controllable standard input. */
class WorkerMainTest {

    @TempDir Path tmp;

    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
    private final PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

    @Test
    void readyLineHealthAndCleanStopOnEndOfInput() throws Exception {
        PipedOutputStream ownerWriter = new PipedOutputStream();
        InputStream owner = new PipedInputStream(ownerWriter);
        Path runtime = tmp.resolve("runtime");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> status =
                    executor.submit(
                            () ->
                                    WorkerMain.run(
                                            new String[] {"--runtime-dir", runtime.toString()},
                                            owner,
                                            out,
                                            err,
                                            () -> Optional.of("0.7.0")));
            JsonObject ready = awaitLine();
            assertEquals(Set.of("event", "instance_id", "manifest"), ready.keySet());
            assertEquals("ready", ready.get("event").getAsString());
            String instance = ready.get("instance_id").getAsString();
            Path manifestPath = Path.of(ready.get("manifest").getAsString());
            assertEquals(runtime.resolve("instances").resolve(instance + ".json"), manifestPath);
            JsonObject manifest =
                    JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();
            assertEquals("worker", manifest.get("host_type").getAsString());
            assertEquals(instance, manifest.get("instance_id").getAsString());
            String token =
                    Files.readString(Path.of(manifest.get("token_file").getAsString())).trim();
            int port = manifest.get("port").getAsInt();

            String health = httpGet(port, "/v1/health", token);
            assertTrue(health.startsWith("HTTP/1.1 200 "), health);
            JsonObject data =
                    JsonParser.parseString(health.substring(health.indexOf("\r\n\r\n") + 4))
                            .getAsJsonObject()
                            .getAsJsonObject("data");
            assertEquals("worker", data.get("host_type").getAsString());
            assertEquals("0.7.0", data.get("qupath_version").getAsString());
            assertTrue(data.get("fx_thread_blocked").isJsonNull());
            assertFalse(outBytes.toString(StandardCharsets.UTF_8).contains(token));
            assertFalse(errBytes.toString(StandardCharsets.UTF_8).contains(token));

            assertFalse(status.isDone(), "the worker blocks while standard input is open");
            ownerWriter.close();
            assertEquals(WorkerMain.EXIT_OK, status.get(10, TimeUnit.SECONDS));
            assertFalse(Files.exists(manifestPath));
            assertFalse(Files.exists(Path.of(manifest.get("token_file").getAsString())));
            assertThrows(IOException.class, () -> new Socket("127.0.0.1", port).close());
            assertEquals(
                    1,
                    outBytes.toString(StandardCharsets.UTF_8).lines().count(),
                    "stdout has one event line");
        }
    }

    @Test
    void refusesUnsupportedAndUnknownQuPathVersions() {
        Path runtime = tmp.resolve("runtime");
        int status =
                WorkerMain.run(
                        new String[] {"--runtime-dir", runtime.toString()},
                        InputStream.nullInputStream(),
                        out,
                        err,
                        () -> Optional.of("0.6.0"));
        assertEquals(WorkerMain.EXIT_UNSUPPORTED_QUPATH, status);
        JsonObject failed =
                JsonParser.parseString(outBytes.toString(StandardCharsets.UTF_8).strip())
                        .getAsJsonObject();
        assertEquals("failed", failed.get("event").getAsString());
        assertEquals("qupath_version_unsupported", failed.get("reason").getAsString());
        assertEquals("0.6.0", failed.get("qupath_version").getAsString());
        assertEquals("0.7.0", failed.get("required_qupath_version").getAsString());
        assertFalse(Files.exists(runtime), "nothing is written for an unsupported QuPath");

        outBytes.reset();
        assertEquals(
                WorkerMain.EXIT_UNSUPPORTED_QUPATH,
                WorkerMain.run(
                        new String[] {"--runtime-dir", runtime.toString()},
                        InputStream.nullInputStream(),
                        out,
                        err,
                        Optional::empty));
        JsonObject unknown =
                JsonParser.parseString(outBytes.toString(StandardCharsets.UTF_8).strip())
                        .getAsJsonObject();
        assertEquals("qupath_version_unknown", unknown.get("reason").getAsString());
        assertTrue(unknown.get("qupath_version").isJsonNull());
    }

    @Test
    void reportsStartFailures() throws IOException {
        Path file = Files.writeString(tmp.resolve("not-a-directory"), "x");
        int status =
                WorkerMain.run(
                        new String[] {"--runtime-dir", file.toString()},
                        InputStream.nullInputStream(),
                        out,
                        err,
                        () -> Optional.of("0.7.0"));
        assertEquals(WorkerMain.EXIT_START_FAILED, status);
        JsonObject failed =
                JsonParser.parseString(outBytes.toString(StandardCharsets.UTF_8).strip())
                        .getAsJsonObject();
        assertEquals("bridge_start_failed", failed.get("reason").getAsString());
    }

    @Test
    void commandLineHandling() {
        for (List<String> args :
                List.of(
                        List.<String>of(),
                        List.of("--runtime-dir"),
                        List.of("--runtime-dir", " "),
                        List.of("--bogus"),
                        List.of("--runtime-dir", tmp.toString(), "extra"))) {
            assertEquals(
                    WorkerMain.EXIT_USAGE,
                    WorkerMain.run(
                            args.toArray(String[]::new),
                            InputStream.nullInputStream(),
                            out,
                            err,
                            () -> Optional.of("0.7.0")),
                    args.toString());
        }
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("usage: WorkerMain"));
        assertEquals(
                WorkerMain.EXIT_OK,
                WorkerMain.run(
                        new String[] {"--help"},
                        InputStream.nullInputStream(),
                        out,
                        err,
                        () -> Optional.of("0.7.0")));
        assertTrue(outBytes.toString(StandardCharsets.UTF_8).startsWith("usage: WorkerMain"));
    }

    @Test
    void versionReportsBuildAndQuPathVersions() {
        assertEquals(
                WorkerMain.EXIT_OK,
                WorkerMain.run(
                        new String[] {"--version"},
                        InputStream.nullInputStream(),
                        out,
                        err,
                        () -> Optional.of("0.7.0")));
        JsonObject version =
                JsonParser.parseString(outBytes.toString(StandardCharsets.UTF_8).strip())
                        .getAsJsonObject();
        assertEquals("version", version.get("event").getAsString());
        assertEquals(
                System.getProperty("qupflowr.test.bridgeVersion"),
                version.get("bridge_version").getAsString());
        assertEquals("1.0", version.get("protocol_version").getAsString());
        assertEquals("0.7.0", version.get("qupath_version").getAsString());
        assertTrue(version.get("qupath_version_supported").getAsBoolean());
        outBytes.reset();
        assertEquals(
                WorkerMain.EXIT_UNSUPPORTED_QUPATH,
                WorkerMain.run(
                        new String[] {"--version"},
                        InputStream.nullInputStream(),
                        out,
                        err,
                        () -> Optional.of("0.7.1")));
    }

    private JsonObject awaitLine() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            String text = outBytes.toString(StandardCharsets.UTF_8);
            if (text.contains("\n")) {
                return JsonParser.parseString(text.substring(0, text.indexOf('\n')))
                        .getAsJsonObject();
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "no ready line; stderr: " + errBytes.toString(StandardCharsets.UTF_8));
    }

    static String httpGet(int port, String path, String token) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream()
                    .write(
                            ("GET "
                                            + path
                                            + " HTTP/1.1\r\nHost: 127.0.0.1:"
                                            + port
                                            + "\r\nAuthorization: Bearer "
                                            + token
                                            + "\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
