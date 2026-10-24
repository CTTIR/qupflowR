/*
 * Copyright (C) 2026 R. Heller
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * qupflowR worker bootstrap for QuPath's command line ("qupath-script" launch profile; no separate
 * Java runtime needed):
 *
 *   <QuPath launcher> script qupflowr-worker-bootstrap.groovy \
 *       --args=<worker jar> --args=<sha256 of the worker jar> --args=<runtime dir>
 *
 * JVM options (explicit heap, -Djava.awt.headless=true, private prefs and temp dirs) are passed
 * through JAVA_TOOL_OPTIONS. The script refuses to run unless the jar's SHA-256 equals the
 * expected digest, then loads io.github.cttir.qupflowr.worker.WorkerMain in its own class loader
 * (parent: the application class loader with QuPath's lib/app jars) and blocks in WorkerMain.run
 * until standard input closes.
 */
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

if (args == null || args.length != 3)
    throw new IllegalArgumentException("expected 3 arguments: <worker jar> <sha256> <runtime dir>")

String expected = args[1].trim().toLowerCase(Locale.ROOT)
if (!(expected ==~ /[0-9a-f]{64}/))
    throw new IllegalArgumentException("expected a hexadecimal SHA-256 digest as second argument")

Path jar = Path.of(args[0]).toRealPath()
MessageDigest digest = MessageDigest.getInstance("SHA-256")
Files.newInputStream(jar).withCloseable { input ->
    byte[] buffer = new byte[65536]
    int n
    while ((n = input.read(buffer)) > 0)
        digest.update(buffer, 0, n)
}
String actual = HexFormat.of().formatHex(digest.digest())
if (actual != expected)
    throw new SecurityException(
        "worker jar digest mismatch: expected " + expected + " but found " + actual)

def loader = new URLClassLoader(
    "qupflowr-worker", [jar.toUri().toURL()] as URL[], ClassLoader.getSystemClassLoader())
Thread.currentThread().setContextClassLoader(loader)
def workerMain = loader.loadClass("io.github.cttir.qupflowr.worker.WorkerMain")
String[] workerArgs = ["--runtime-dir", args[2]] as String[]
def run = workerMain.getMethod("run", String[].class)
int status = (int) run.invoke(null, [workerArgs] as Object[])
System.err.println("qupflowR worker bootstrap: WorkerMain.run returned " + status)
if (status != 0)
    throw new IllegalStateException("worker exited with status " + status)
return status
