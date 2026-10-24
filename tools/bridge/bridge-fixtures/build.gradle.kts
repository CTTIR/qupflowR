// bridge-fixtures: synthetic image and QuPath project fixtures with independent expected values.
//
// Source sets:
//   main    JDK only. Formulas and constants (the oracle), the object specification, the JDK
//           TIFF writers, the raw TIFF inspector, JSON output, output normalisation and the
//           manifest. Its unit tests run without QuPath, Bio-Formats or any third-party jar.
//   qupath  Bio-Formats OME-TIFF writer, QuPath project writer and the generator main class.
//           Compiled against the jars of the pinned QuPath 0.7.0 installation (sha256 pinned
//           below; file dependencies are outside Gradle's dependency verification, so this
//           build checks them itself). At run time the generator uses <QUPATH_HOME>/lib/app/*.
//
// Tasks:
//   jar                        build/libs/qupflowr-bridge-fixtures-<version>.jar (both sets)
//   verifyQuPathCompileJars    checks presence and sha256 of the QuPath jars used to compile
//
// The QuPath installation is taken from -Pqupflowr.qupathHome, else the environment variable
// QUPFLOWR_QUPATH_HOME, else /opt/QuPath-v0.7.0. The generator is run by tools/fixtures.R.

import java.security.MessageDigest

plugins {
    `java-library`
}

description = "qupflowR synthetic fixture generator (images, QuPath project, expected values)"

val generatorMainClass = "io.github.cttir.qupflowr.fixtures.qupath.FixtureGeneratorMain"

val qupathHome: Provider<String> = providers.gradleProperty("qupflowr.qupathHome")
    .orElse(providers.environmentVariable("QUPFLOWR_QUPATH_HOME"))
    .orElse("/opt/QuPath-v0.7.0")

/** QuPath 0.7.0 installation jars the qupath source set compiles against (lib/app). */
val qupathCompileJars = linkedMapOf(
    "qupath-core-0.7.0.jar" to
        "31fbcd9e19544bf50a3ca00c8792eb0e94da9b0fb9182bd3c8ec3d77130ded61",
    "jts-core-1.20.0.jar" to
        "6a783d8f9dba3d3cf7265435f134402f63c05838aa6cbcc4297ad3a5b2842baf",
    "formats-api-8.4.0.jar" to
        "2388d0103696cfd012cf638f25a6c61555e4ca9e22f3c1b3e0eab0dda73f9068",
    "formats-bsd-8.4.0.jar" to
        "6d3e30d21207f682d9d382d4f22eed43d07490f93db396ad5b73d4a9e520ee70",
    "ome-common-6.1.2.jar" to
        "00b79443f16e3f6ba5b53ebfb66d6e37ba52c8ea1f51470e0ab56c6afaf2678a",
    "ome-xml-6.5.1.jar" to
        "a467fef43aa4997d07ce2e586ab71f49c5fb470282e6fce539b32b0bd576c1e1",
    "gson-2.13.2.jar" to
        "dd0ce1b55a3ed2080cb70f9c655850cda86c206862310009dcb5e5c95265a5e0",
    "slf4j-api-2.0.17.jar" to
        "7b751d952061954d5abfed7181c1f645d336091b679891591d63329c622eb832",
)

val qupathJarFiles: Provider<List<File>> = qupathHome.map { home ->
    qupathCompileJars.keys.map { File(home, "lib/app/$it") }
}

// Committed expected-value tables shared with the R tests (tests/fixtures/qp-v1). Override with
// -Pqupflowr.fixtureTablesDir=<dir> when building from a copy of tools/bridge.
val fixtureTablesDir: Provider<Directory> = providers
    .gradleProperty("qupflowr.fixtureTablesDir")
    .map { layout.projectDirectory.dir(file(it).absolutePath) }
    .orElse(layout.projectDirectory.dir("../../../tests/fixtures/qp-v1"))

val qupath: SourceSet = sourceSets.create("qupath") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

dependencies {
    "qupathCompileOnly"(files(qupathJarFiles))

    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

val verifyQuPathCompileJars by tasks.registering {
    group = "verification"
    description = "Checks the QuPath 0.7.0 installation jars used to compile the qupath source set"
    val home = qupathHome
    val pinned = qupathCompileJars.toMap()
    inputs.property("qupathHome", home)
    inputs.property("pinned", pinned)
    val report = layout.buildDirectory.file("reports/qupath-compile-jars.txt")
    outputs.file(report)
    doLast {
        val problems = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for ((name, expected) in pinned) {
            val jar = File(home.get(), "lib/app/$name")
            if (!jar.isFile) {
                problems += "missing $jar"
                continue
            }
            val actual = sha256(jar)
            lines += "$actual  $name"
            if (actual != expected) problems += "$jar has sha256 $actual, expected $expected"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The qupath source set needs the QuPath 0.7.0 installation " +
                    "(set -Pqupflowr.qupathHome or QUPFLOWR_QUPATH_HOME):\n" +
                    problems.joinToString("\n")
            )
        }
        report.get().asFile.apply { parentFile.mkdirs() }
            .writeText(lines.joinToString("\n") + "\n")
    }
}

tasks.named<JavaCompile>(qupath.compileJavaTaskName) {
    dependsOn(verifyQuPathCompileJars)
}

tasks.jar {
    archiveFileName = "qupflowr-bridge-fixtures-${project.version}.jar"
    from(qupath.output)
    manifest {
        attributes(
            "Main-Class" to generatorMainClass,
            "Automatic-Module-Name" to "io.github.cttir.qupflowr.fixtures",
        )
    }
}

tasks.processResources {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.test {
    systemProperty("qupflowr.test.fixtureTables", fixtureTablesDir.get().asFile.absolutePath)
    inputs.dir(fixtureTablesDir).withPropertyName("fixtureTables")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
