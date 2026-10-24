/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.cttir.qupflowr.worker;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Starts isolated child JVMs for worker tests and collects their output lines. */
final class ChildJvm {

    private final Process process;
    private final List<String> stdout = new CopyOnWriteArrayList<>();
    private final List<String> stderr = new CopyOnWriteArrayList<>();
    private final Thread stdoutPump;
    private final Thread stderrPump;

    private ChildJvm(Process process) {
        this.process = process;
        this.stdoutPump = pump(process.getInputStream(), stdout);
        this.stderrPump = pump(process.getErrorStream(), stderr);
    }

    /**
     * Class path of the test JVM without any output of the bridge build itself: third-party jars
     * (QuPath core, Gson, Logback, Groovy) only.
     */
    static List<String> thirdPartyClasspath() {
        String bridgeRoot =
                Path.of(System.getProperty("qupflowr.test.bridgeRoot")).toAbsolutePath().toString();
        List<String> entries = new ArrayList<>();
        for (String entry :
                System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (!Path.of(entry).toAbsolutePath().toString().startsWith(bridgeRoot)) {
                entries.add(entry);
            }
        }
        return entries;
    }

    static ChildJvm start(Path workDir, List<String> classpath, List<String> arguments)
            throws IOException {
        String javaExecutable = ProcessHandle.current().info().command().orElseThrow();
        List<String> command =
                new ArrayList<>(
                        List.of(
                                javaExecutable,
                                "-Djava.awt.headless=true",
                                "-Dprism.order=sw",
                                "-Duser.home=" + workDir.resolve("home"),
                                "-Djava.util.prefs.userRoot=" + workDir.resolve("prefs"),
                                "-Djava.io.tmpdir=" + workDir.resolve("tmp"),
                                "-Djna.tmpdir=" + workDir.resolve("tmp"),
                                "-Dlogback.configurationFile="
                                        + System.getProperty("qupflowr.test.logbackConfig"),
                                "-cp",
                                String.join(java.io.File.pathSeparator, classpath)));
        command.addAll(arguments);
        workDir.resolve("tmp").toFile().mkdirs();
        ProcessBuilder builder = new ProcessBuilder(command);
        Map<String, String> env = builder.environment();
        for (String name :
                List.of(
                        "DISPLAY",
                        "WAYLAND_DISPLAY",
                        "JAVA_TOOL_OPTIONS",
                        "JDK_JAVA_OPTIONS",
                        "_JAVA_OPTIONS",
                        "CLASSPATH")) {
            env.remove(name);
        }
        env.put("HOME", workDir.resolve("home").toString());
        return new ChildJvm(builder.start());
    }

    Process process() {
        return process;
    }

    List<String> stdout() {
        return stdout;
    }

    List<String> stderr() {
        return stderr;
    }

    /** Waits for the first stdout line that is a JSON event. */
    JsonObject awaitEvent(long timeoutMillis) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < end) {
            for (String line : stdout) {
                if (line.startsWith("{\"event\":")) {
                    return JsonParser.parseString(line).getAsJsonObject();
                }
            }
            if (!process.isAlive() && stdout.stream().noneMatch(l -> l.startsWith("{\"event\":"))) {
                Thread.sleep(200); // let the pumps drain
                for (String line : stdout) {
                    if (line.startsWith("{\"event\":")) {
                        return JsonParser.parseString(line).getAsJsonObject();
                    }
                }
                throw new AssertionError(
                        "child exited with "
                                + process.exitValue()
                                + " without an event; stderr: "
                                + stderr);
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "no event line within "
                        + timeoutMillis
                        + " ms; stdout "
                        + stdout
                        + " stderr "
                        + stderr);
    }

    int awaitExit(long timeoutMillis) throws InterruptedException {
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(
                    "child did not exit within " + timeoutMillis + " ms; stderr " + stderr);
        }
        stdoutPump.join(5000); // the pumps end at end of stream
        stderrPump.join(5000);
        return process.exitValue();
    }

    void kill() {
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private static Thread pump(java.io.InputStream stream, List<String> sink) {
        return Thread.ofPlatform()
                .daemon()
                .start(
                        () -> {
                            try (BufferedReader reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    stream, StandardCharsets.UTF_8))) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    sink.add(line);
                                }
                            } catch (IOException e) {
                                // process ended
                            }
                        });
    }
}
