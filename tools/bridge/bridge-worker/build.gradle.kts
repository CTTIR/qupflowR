// bridge-worker: headless worker (WorkerMain) and its distribution zip with launch profiles.

import java.security.MessageDigest

plugins {
    `java-library`
}

description = "qupflowR headless worker serving the bridge protocol"

val bridgeVersion = project.version.toString()
val protocolVersion: String = providers.gradleProperty("qupflowr.protocolVersion").get()
val qupathVersion: String = libs.versions.qupath.asProvider().get()
val javaFeatureVersion: String = libs.versions.jdk.get()
val distName = "qupflowr-bridge-worker-$bridgeVersion"
val jarName = "qupflowr-bridge-worker-$bridgeVersion.jar"
val mainClassName = "io.github.cttir.qupflowr.worker.WorkerMain"
val bootstrapPath = "bootstrap/qupflowr-worker-bootstrap.groovy"
val bootstrapSource = layout.projectDirectory.file("src/dist/$bootstrapPath")
val logbackSource = layout.projectDirectory.file("src/dist/config/logback-worker.xml")

dependencies {
    implementation(project(":bridge-core"))

    compileOnly(libs.gson)
    compileOnly(libs.slf4j)
    compileOnly(libs.qupath.core)

    testImplementation(libs.junit)
    testImplementation(libs.gson)
    // Real QuPath core (GeneralTools) for the child-process tests; brings logback at run time.
    testRuntimeOnly(libs.qupath.core)
    // Runs the Groovy bootstrap the same way QuPath's script command does.
    testImplementation(libs.groovy.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Merge the classes of our own project dependencies (never third-party jars) into the worker jar.
val bundledProjectJars: FileCollection = configurations.runtimeClasspath.get().incoming
    .artifactView { componentFilter { it is ProjectComponentIdentifier } }.files

tasks.jar {
    archiveFileName = jarName
    dependsOn(bundledProjectJars)
    from(bundledProjectJars.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Automatic-Module-Name" to "io.github.cttir.qupflowr.worker",
        )
    }
}

tasks.processResources {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun jsonString(value: String): String = buildString {
    append('"')
    for (c in value) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/** Deterministic JSON writer (maps keep insertion order, two-space indentation). */
fun toJson(value: Any?, indent: String = ""): String {
    val next = "$indent  "
    return when (value) {
        null -> "null"
        is String -> jsonString(value)
        is Boolean, is Int, is Long -> value.toString()
        is Map<*, *> ->
            if (value.isEmpty()) {
                "{}"
            } else {
                value.entries.joinToString(",\n", "{\n", "\n$indent}") {
                    "$next${jsonString(it.key.toString())}: ${toJson(it.value, next)}"
                }
            }
        is List<*> ->
            if (value.isEmpty()) {
                "[]"
            } else {
                value.joinToString(",\n", "[\n", "\n$indent]") { "$next${toJson(it, next)}" }
            }
        else -> throw GradleException("Unsupported JSON value ${value::class}")
    }
}

/** launch.json: everything R needs to start a worker (placeholders are documented inside). */
fun launchDescription(jarSha256: String, bootstrapSha256: String): Map<String, Any?> {
    val isolation = listOf(
        "-Djava.awt.headless=true",
        "-Dprism.order=sw",
        "-Djava.util.prefs.userRoot=<PREFS_DIR>",
        "-Djava.io.tmpdir=<TMP_DIR>",
        "-Djna.tmpdir=<TMP_DIR>",
        "-Dorg.bytedeco.javacpp.cachedir=<JAVACPP_CACHE_DIR>",
    )
    val removeEnvironment = listOf(
        "DISPLAY", "WAYLAND_DISPLAY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS",
        "CLASSPATH",
    )
    val javaProfile = linkedMapOf(
        "id" to "java",
        "preference" to 1,
        "requires" to "a Java $javaFeatureVersion runtime (development: .tools/jdk-25)",
        "preflight" to listOf(listOf("<JAVA>", "-cp", "<CLASSPATH>", mainClassName, "--version")),
        "preflight_expect" to "exit code 0 and qupath_version_supported true",
        "options_channel" to "command_line",
        "jvm_options" to isolation + listOf(
            "-Dlogback.configurationFile=<DIST>/config/logback-worker.xml",
        ),
        "command" to listOf(
            "<JAVA>", "<JVM_OPTIONS>", "-cp", "<CLASSPATH>", mainClassName,
            "--runtime-dir", "<RUNTIME_DIR>",
        ),
        "environment" to linkedMapOf(
            "remove" to removeEnvironment,
            "set" to emptyMap<String, String>(),
        ),
    )
    val scriptProfile = linkedMapOf(
        "id" to "qupath-script",
        "preference" to 2,
        "requires" to "a working QuPath launcher (no separate Java runtime)",
        "preflight" to listOf(listOf("<QUPATH_LAUNCHER>", "--version")),
        "preflight_expect" to "exit code 0 and output containing $qupathVersion",
        "launcher_candidates" to linkedMapOf(
            "linux" to linkedMapOf(
                "path" to "bin/QuPath",
                "tested" to "crashes before JVM creation on the reference host (JDK-8380085)",
            ),
            "macos" to linkedMapOf("path" to "Contents/MacOS/QuPath", "tested" to "no"),
            "windows" to linkedMapOf(
                "path" to "QuPath-$qupathVersion (console).exe",
                "tested" to "no",
            ),
        ),
        "options_channel" to "JAVA_TOOL_OPTIONS",
        "jvm_options" to listOf("-Xmx<HEAP>") + isolation,
        "default_heap" to "2g",
        "heap_note" to "QuPath.cfg sets -XX:MaxRAMPercentage=50; always pass an explicit heap",
        "command" to listOf(
            "<QUPATH_LAUNCHER>", "script", "<DIST>/$bootstrapPath",
            "--args=<DIST>/lib/$jarName", "--args=<WORKER_JAR_SHA256>", "--args=<RUNTIME_DIR>",
        ),
        "environment" to linkedMapOf(
            "remove" to removeEnvironment - "JAVA_TOOL_OPTIONS",
            "set" to linkedMapOf(
                "JAVA_TOOL_OPTIONS" to "<JVM_OPTIONS>, each quoted as java_tool_options_quoting " +
                    "says, joined with single spaces",
            ),
        ),
        "java_tool_options_quoting" to "The JVM splits JAVA_TOOL_OPTIONS at white space and " +
            "knows no backslash escapes. Enclose every option in single quotes, or in double " +
            "quotes when it contains a single quote; an option that contains both quote " +
            "characters cannot be passed, so choose directories without them. Unquoted, a " +
            "placeholder path with a space stops the JVM ('Unrecognized option').",
        "known_defects" to listOf(
            linkedMapOf(
                "id" to "JDK-8380085",
                "platform" to "linux",
                "effect" to "the QuPath $qupathVersion jpackage launcher can crash before the " +
                    "JVM starts; the preflight detects this and the java profile is used instead",
            ),
        ),
    )
    return linkedMapOf(
        "launch_schema_version" to "1.0",
        "component" to "qupflowr-bridge-worker",
        "bridge_version" to bridgeVersion,
        "protocol_version" to protocolVersion,
        "qupath_version" to qupathVersion,
        "java_feature_version" to javaFeatureVersion.toInt(),
        "main_class" to mainClassName,
        "worker_jar" to linkedMapOf("path" to "lib/$jarName", "sha256" to jarSha256),
        "bootstrap" to linkedMapOf("path" to bootstrapPath, "sha256" to bootstrapSha256),
        "checksums" to "checksums.sha256",
        "placeholders" to linkedMapOf(
            "<DIST>" to "absolute path of the directory containing this launch.json",
            "<QUPATH_HOME>" to "QuPath $qupathVersion installation directory (contains lib/app)",
            "<JAVA>" to "java executable of a Java $javaFeatureVersion runtime",
            "<QUPATH_LAUNCHER>" to "QuPath launcher executable of <QUPATH_HOME>",
            "<CLASSPATH>" to "the classpath entries joined with the platform path separator",
            "<JVM_OPTIONS>" to "the profile's jvm_options, each as its own argument",
            "<RUNTIME_DIR>" to "private runtime directory for discovery files (0700)",
            "<PREFS_DIR>" to "private java.util.prefs root of this worker",
            "<TMP_DIR>" to "private temporary directory of this worker",
            "<JAVACPP_CACHE_DIR>" to "JavaCPP native library cache (OpenCV), not in user.home",
            "<HEAP>" to "maximum Java heap, e.g. 2g",
            "<WORKER_JAR_SHA256>" to "worker_jar.sha256",
        ),
        "classpath" to listOf("<DIST>/lib/$jarName", "<QUPATH_HOME>/lib/app/*"),
        "events" to linkedMapOf(
            "stream" to "stdout",
            "format" to "one JSON object per line; event lines start with {\"event\":",
            "ready" to listOf("event", "instance_id", "manifest"),
            "failed" to listOf("event", "reason"),
            "token_in_output" to false,
        ),
        "owner_signal" to "closing standard input stops the worker",
        "exit_codes" to linkedMapOf(
            "stdin_closed" to 0,
            "start_failed" to 1,
            "usage" to 2,
            "qupath_version_unsupported" to 3,
            "sigterm" to 143,
        ),
        "profiles" to listOf(javaProfile, scriptProfile),
    )
}

val generateLaunchJson by tasks.registering {
    group = "distribution"
    description = "Writes launch.json: QuPath version, class path, JVM options and launch profiles"
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jarFile)
    inputs.file(bootstrapSource)
    val versions = listOf(bridgeVersion, protocolVersion, qupathVersion, javaFeatureVersion)
    inputs.property("versions", versions)
    val out = layout.buildDirectory.file("generated/worker-dist/launch.json")
    outputs.file(out)
    doLast {
        val launch = launchDescription(sha256(jarFile.get().asFile), sha256(bootstrapSource.asFile))
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(toJson(launch) + "\n")
    }
}

val stageWorkerDistribution by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Stages the worker distribution directory"
    into(layout.buildDirectory.dir("worker-dist/$distName"))
    from(tasks.jar) { into("lib") }
    from("src/dist/bootstrap") { into("bootstrap") }
    from("src/dist/config") { into("config") }
    from("src/dist/THIRD-PARTY.md")
    from(rootProject.file("LICENSE"))
    from(generateLaunchJson)
}

val writeWorkerChecksums by tasks.registering {
    group = "distribution"
    description = "Writes checksums.sha256 for every file of the staged worker distribution"
    val staged = layout.buildDirectory.dir("worker-dist/$distName")
    dependsOn(stageWorkerDistribution)
    inputs.dir(staged)
    val out = layout.buildDirectory.file("generated/worker-checksums/checksums.sha256")
    outputs.file(out)
    doLast {
        val root = staged.get().asFile
        val lines = root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .map { (path, file) -> "${sha256(file)}  $path" }
            .toList()
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n") + "\n")
    }
}

val workerDistribution by tasks.registering(Zip::class) {
    group = "distribution"
    description = "build/distributions/qupflowr-bridge-worker-<version>.zip"
    archiveFileName = "$distName.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    into(distName) {
        from(stageWorkerDistribution)
        from(writeWorkerChecksums)
    }
}

tasks.named("assemble") {
    dependsOn(workerDistribution)
}

tasks.test {
    dependsOn(tasks.jar)
    val workerJar = tasks.jar.get().archiveFile.get().asFile
    systemProperty("qupflowr.test.workerJar", workerJar.absolutePath)
    systemProperty("qupflowr.test.bootstrap", bootstrapSource.asFile.absolutePath)
    systemProperty("qupflowr.test.bridgeRoot", rootProject.projectDir.absolutePath)
    systemProperty("qupflowr.test.logbackConfig", logbackSource.asFile.absolutePath)
    maxParallelForks = 1
}
