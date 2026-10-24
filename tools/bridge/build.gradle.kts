// Root build of the qupflowR Java bridge. Module builds live in bridge-*/build.gradle.kts.
//
// Root tasks:
//   verifyJarContents  inspects extension and worker jars (own classes, manifest, services)
//   bridgeBundle       build/distributions/qupflowr-bridge-bundle-<version>.zip (+ .sha256)
//   resolveAndLockAll  resolves every configuration (use with --write-locks and
//                      --write-verification-metadata sha256, see README.md)

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.security.MessageDigest
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    base
}

description = "qupflowR Java bridge: protocol, loopback HTTP core, QuPath extension and worker"

val bridgeVersion = version.toString()
val qupathVersion: String = libs.versions.qupath.asProvider().get()
val javaFeatureVersion: String = libs.versions.jdk.get()
val protocolVersion: String = providers.gradleProperty("qupflowr.protocolVersion").get()

allprojects {
    dependencyLocking {
        lockAllConfigurations()
        // Every resolved configuration needs lock state; refresh with --write-locks (README.md).
        lockMode = LockMode.STRICT
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        dirPermissions { unix("rwxr-xr-x") }
        filePermissions { unix("rw-r--r--") }
    }

    tasks.register("resolveAndLockAll") {
        group = "dependency locking"
        description = "Resolves all resolvable configurations (locks, verification metadata)"
        notCompatibleWithConfigurationCache("Resolves configurations at execution time")
        val resolvable = configurations.matching { it.isCanBeResolved }
        doLast {
            resolvable.forEach { it.resolve() }
        }
    }
}

subprojects {
    plugins.withType<JavaPlugin>().configureEach {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(javaFeatureVersion)
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }

        // The QuPath conventions plugin configures Javadoc of the extension itself; the other
        // modules are checked strictly (doclint without the "missing" group, warnings are errors).
        if (project.name != "bridge-extension") {
            tasks.withType<Javadoc>().configureEach {
                (options as StandardJavadocDocletOptions).apply {
                    encoding = "UTF-8"
                    addBooleanOption("Xdoclint:all,-missing", true)
                    addBooleanOption("Werror", true)
                    addBooleanOption("quiet", true)
                }
            }
            tasks.named("check") {
                dependsOn(tasks.named("javadoc"))
            }
        }

        tasks.withType<Jar>().configureEach {
            manifest {
                attributes(
                    "Implementation-Title" to project.name,
                    "Implementation-Version" to bridgeVersion,
                    "QuPath-Version" to qupathVersion,
                )
            }
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Test JVMs never see the user's display, JVM option variables or profile directories.
            val blocked = setOf(
                "DISPLAY", "WAYLAND_DISPLAY", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS",
                "_JAVA_OPTIONS",
            )
            setEnvironment(environment.filterKeys { it !in blocked })
            val isolationDir = layout.buildDirectory.dir("test-isolation").get().asFile
            systemProperty("user.home", isolationDir.resolve("home").absolutePath)
            systemProperty("java.util.prefs.userRoot", isolationDir.resolve("prefs").absolutePath)
            systemProperty("java.io.tmpdir", isolationDir.resolve("tmp").absolutePath)
            systemProperty("java.awt.headless", "true")
            systemProperty("prism.order", "sw")
            systemProperty("qupflowr.test.bridgeVersion", bridgeVersion)
            systemProperty("qupflowr.test.protocolVersion", protocolVersion)
            systemProperty("qupflowr.test.qupathVersion", qupathVersion)
            doFirst {
                listOf("home", "prefs", "tmp").forEach { isolationDir.resolve(it).mkdirs() }
            }
            testLogging {
                events("failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}

// ---------------------------------------------------------------------------------- jar checks

/** Expected layout of one of our jars. */
data class JarRule(
    val label: String,
    val requiredPackages: List<String>,
    val forbiddenPackages: List<String>,
    val services: Map<String, String>,
    val mainClass: String?,
)

val ownPrefix = "io/github/cttir/qupflowr/"
val extensionService = "META-INF/services/qupath.lib.gui.extensions.QuPathExtension"
val allowedMetaInf = setOf(
    "META-INF/", "META-INF/MANIFEST.MF", "META-INF/LICENSE", "META-INF/services/",
)
val knownForeign = listOf(
    "qupath/", "javafx/", "com/google/gson/", "org/slf4j/", "ch/qos/logback/", "groovy/",
    "org/apache/groovy/", "kotlin/", "module-info.class",
)

val sharedPackages = listOf("protocol/", "core/http/", "core/bridge/", "core/qupath/")

val extensionRule = JarRule(
    label = "extension",
    requiredPackages = sharedPackages + "extension/",
    forbiddenPackages = listOf("worker/"),
    services = mapOf(extensionService to "io.github.cttir.qupflowr.extension.QupflowrExtension"),
    mainClass = null,
)

val workerRule = JarRule(
    label = "worker",
    requiredPackages = sharedPackages + "worker/",
    forbiddenPackages = listOf("extension/"),
    services = emptyMap(),
    mainClass = "io.github.cttir.qupflowr.worker.WorkerMain",
)

/** Returns violations of [rule] for [jar]; empty when the jar is acceptable. */
fun jarViolations(jar: File, rule: JarRule, version: String, qupath: String): List<String> {
    val problems = mutableListOf<String>()
    fun problem(text: String) {
        problems += "${rule.label}: $text"
    }
    JarFile(jar).use { jf ->
        val names = jf.entries().asSequence().map { it.name }.toList()
        for (name in names) {
            val foreign = knownForeign.firstOrNull { name.startsWith(it) }
            val ownParent = name == "io/" || name == "io/github/" || name == "io/github/cttir/"
            when {
                foreign != null -> problem("bundled third-party entry $name")
                name.startsWith("META-INF/") ->
                    if (name !in allowedMetaInf && !rule.services.containsKey(name)) {
                        problem("unexpected META-INF entry $name")
                    }
                ownParent -> Unit
                !name.startsWith(ownPrefix) -> problem("entry outside $ownPrefix: $name")
            }
        }
        for (pkg in rule.requiredPackages) {
            if (names.none { it.startsWith(ownPrefix + pkg) && it.endsWith(".class") }) {
                problem("no classes from package ${ownPrefix + pkg}")
            }
        }
        for (pkg in rule.forbiddenPackages) {
            names.filter { it.startsWith(ownPrefix + pkg) }
                .forEach { problem("entry must not be in this jar: $it") }
        }
        for ((service, impl) in rule.services) {
            val entry = jf.getJarEntry(service)
            if (entry == null) {
                problem("missing $service")
                continue
            }
            val lines = jf.getInputStream(entry).bufferedReader().readLines()
                .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            if (lines != listOf(impl)) problem("$service lists $lines, expected [$impl]")
            if (names.none { it == impl.replace('.', '/') + ".class" }) {
                problem("service implementation $impl is not in the jar")
            }
        }
        val services = names.filter { it.startsWith("META-INF/services/") && !it.endsWith("/") }
        if (rule.services.isEmpty() && services.isNotEmpty()) {
            problem("unexpected service registration $services")
        }
        val attributes = jf.manifest?.mainAttributes
        if (attributes == null) {
            problem("missing manifest")
        } else {
            val expected = mapOf(
                "Implementation-Version" to version,
                "QuPath-Version" to qupath,
                "Main-Class" to rule.mainClass,
            )
            for ((key, value) in expected) {
                val actual = attributes.getValue(key)
                if (actual != value) problem("$key is $actual, expected $value")
            }
        }
        if (names.none { it == "META-INF/LICENSE" }) problem("missing META-INF/LICENSE")
    }
    return problems
}

/** Writes a synthetic jar for the checker's negative controls. */
fun writeSyntheticJar(target: File, entries: Map<String, String>, manifest: Map<String, String>) {
    target.parentFile.mkdirs()
    val mf = Manifest()
    mf.mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
    manifest.forEach { (k, v) -> mf.mainAttributes.putValue(k, v) }
    JarOutputStream(target.outputStream(), mf).use { out ->
        entries.forEach { (name, content) ->
            out.putNextEntry(JarEntry(name))
            out.write(content.toByteArray())
            out.closeEntry()
        }
    }
}

/** One negative (or positive) control of the jar checker. */
data class JarControl(
    val name: String,
    val entries: Map<String, String>,
    val manifest: Map<String, String>,
    val expectFragment: String?,
)

val verifyJarContentsSelfTest by tasks.registering {
    group = "verification"
    description = "Negative controls: the jar checker rejects third-party classes and bad layouts"
    val scratch = layout.buildDirectory.dir("tmp/jar-check-self-test")
    outputs.dir(scratch)
    doLast {
        val dir = scratch.get().asFile
        dir.deleteRecursively()
        val manifest = mapOf("Implementation-Version" to "1.2.3", "QuPath-Version" to "0.7.0")
        val classes = (sharedPackages + "extension/").map { "${it}A" }
        val good = classes.associate { "$ownPrefix$it.class" to "x" } + mapOf(
            "${ownPrefix}extension/QupflowrExtension.class" to "x",
            extensionService to "io.github.cttir.qupflowr.extension.QupflowrExtension\n",
            "META-INF/LICENSE" to "GPL",
        )
        val third = "bundled third-party entry"
        val controls = listOf(
            JarControl("good", good, manifest, null),
            JarControl("gson", good + ("com/google/gson/Gson.class" to "x"), manifest,
                "$third com/google/gson/Gson.class"),
            JarControl("qupath", good + ("qupath/lib/common/Version.class" to "x"), manifest,
                "$third qupath/"),
            JarControl("javafx", good + ("javafx/scene/Node.class" to "x"), manifest,
                "$third javafx/"),
            JarControl("no-service", good - extensionService, manifest,
                "missing $extensionService"),
            JarControl("worker-classes", good + ("${ownPrefix}worker/WorkerMain.class" to "x"),
                manifest, "must not be in this jar"),
            JarControl("wrong-qupath", good, manifest + ("QuPath-Version" to "0.6.0"),
                "QuPath-Version is 0.6.0"),
            JarControl("foreign-root", good + ("org/example/Evil.class" to "x"), manifest,
                "entry outside"),
        )
        val failures = mutableListOf<String>()
        for (control in controls) {
            val jar = dir.resolve("${control.name}.jar")
            writeSyntheticJar(jar, control.entries, control.manifest)
            val violations = jarViolations(jar, extensionRule, "1.2.3", "0.7.0")
            val fragment = control.expectFragment
            if (fragment == null && violations.isNotEmpty()) {
                failures += "control ${control.name}: expected no violations, got $violations"
            }
            if (fragment != null && violations.none { it.contains(fragment) }) {
                failures += "control ${control.name}: expected '$fragment', got $violations"
            }
        }
        if (failures.isNotEmpty()) throw GradleException(failures.joinToString("\n"))
        logger.lifecycle("verifyJarContentsSelfTest: ${controls.size} controls behaved as expected")
    }
}

val extensionJarFile = project(":bridge-extension").layout.buildDirectory
    .file("libs/qupflowr-bridge-extension-$bridgeVersion.jar")
val workerJarFile = project(":bridge-worker").layout.buildDirectory
    .file("libs/qupflowr-bridge-worker-$bridgeVersion.jar")
val workerDistributionFile = project(":bridge-worker").layout.buildDirectory
    .file("distributions/qupflowr-bridge-worker-$bridgeVersion.zip")

fun sha256Hex(file: File): String {
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

val verifyJarContents by tasks.registering {
    group = "verification"
    description = "Checks that the extension and worker jars contain only qupflowR classes"
    dependsOn(verifyJarContentsSelfTest, ":bridge-extension:jar", ":bridge-worker:jar")
    inputs.file(extensionJarFile)
    inputs.file(workerJarFile)
    val report = layout.buildDirectory.file("reports/jar-contents/report.txt")
    outputs.file(report)
    doLast {
        val lines = mutableListOf<String>()
        val problems = mutableListOf<String>()
        val jars = listOf(
            extensionJarFile.get().asFile to extensionRule,
            workerJarFile.get().asFile to workerRule,
        )
        for ((file, rule) in jars) {
            val violations = jarViolations(file, rule, bridgeVersion, qupathVersion)
            val entries = ZipFile(file).use { zf -> zf.entries().asSequence().count() }
            lines += "${rule.label}\t${file.name}\tentries=$entries\tsha256=${sha256Hex(file)}" +
                "\tviolations=${violations.size}"
            problems += violations
        }
        val text = (lines + problems).joinToString("\n") + "\n"
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
        lines.forEach { logger.lifecycle(it) }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

tasks.named("check") {
    dependsOn(verifyJarContents)
}

// ---------------------------------------------------------------------------------- bundle

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

/** Minimal deterministic JSON writer for build metadata (maps keep insertion order). */
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
        else -> throw GradleException("Unsupported JSON value type ${value::class}")
    }
}

val bridgeBundleManifest by tasks.registering {
    group = "distribution"
    description = "Writes manifest.json with the sha256 of every bridge bundle member"
    dependsOn(":bridge-extension:jar", ":bridge-worker:workerDistribution")
    inputs.file(extensionJarFile)
    inputs.file(workerDistributionFile)
    inputs.property("versions", listOf(bridgeVersion, protocolVersion, qupathVersion))
    val out = layout.buildDirectory.file("bundle/manifest.json")
    outputs.file(out)
    doLast {
        val ext = extensionJarFile.get().asFile
        val dist = workerDistributionFile.get().asFile
        val distRoot = "qupflowr-bridge-worker-$bridgeVersion"
        ZipFile(dist).use { zf ->
            for (member in listOf("launch.json", "checksums.sha256")) {
                if (zf.getEntry("$distRoot/$member") == null) {
                    throw GradleException("$dist lacks $distRoot/$member")
                }
            }
        }
        val manifest = linkedMapOf(
            "bundle_schema_version" to "1.0",
            "name" to "qupflowr-bridge-bundle",
            "bridge_version" to bridgeVersion,
            "protocol_version" to protocolVersion,
            "qupath_version" to qupathVersion,
            "java_feature_version" to javaFeatureVersion.toInt(),
            "members" to listOf(
                linkedMapOf(
                    "path" to ext.name,
                    "role" to "extension",
                    "install" to "copy into the extensions directory of the QuPath user directory",
                    "size" to ext.length(),
                    "sha256" to sha256Hex(ext),
                ),
                linkedMapOf(
                    "path" to dist.name,
                    "role" to "worker_distribution",
                    "root" to distRoot,
                    "launch" to "$distRoot/launch.json",
                    "checksums" to "$distRoot/checksums.sha256",
                    "size" to dist.length(),
                    "sha256" to sha256Hex(dist),
                ),
            ),
        )
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(toJson(manifest) + "\n")
    }
}

val bridgeBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Bridge bundle: extension jar, worker distribution and manifest.json"
    dependsOn(verifyJarContents)
    val bundleName = "qupflowr-bridge-bundle-$bridgeVersion.zip"
    archiveFileName = bundleName
    destinationDirectory = layout.buildDirectory.dir("distributions")
    from(extensionJarFile)
    from(workerDistributionFile)
    from(bridgeBundleManifest)
    val sidecar = layout.buildDirectory.file("distributions/$bundleName.sha256")
    outputs.file(sidecar)
    doLast {
        val zip = archiveFile.get().asFile
        val names = ZipFile(zip).use { zf ->
            zf.entries().asSequence().map(ZipEntry::getName).toList()
        }
        val expected = listOf(
            "manifest.json",
            extensionJarFile.get().asFile.name,
            workerDistributionFile.get().asFile.name,
        )
        if (names.sorted() != expected.sorted()) {
            throw GradleException("bundle members $names, expected $expected")
        }
        sidecar.get().asFile.writeText("${sha256Hex(zip)}  ${zip.name}\n")
        logger.lifecycle("bridgeBundle: ${zip.absolutePath}")
    }
}

tasks.named("assemble") {
    dependsOn(bridgeBundle)
}
