// bridge-protocol: protocol v1 DTOs, error-code registry and JSON rules. No QuPath dependency.

plugins {
    `java-library`
}

description = "qupflowR bridge protocol v1 (DTOs, error codes, JSON rules; no QuPath dependency)"

val bridgeVersion = project.version.toString()
val protocolVersion = providers.gradleProperty("qupflowr.protocolVersion").get()
val qupathVersion: String = libs.versions.qupath.asProvider().get()

dependencies {
    // Gson is provided at run time by QuPath (lib/app) and must never be bundled.
    compileOnly(libs.gson)

    testImplementation(libs.gson)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The shared schema tree of the R package (inst/schema). Override with -Pqupflowr.schemaDir=<dir>
// when building from a copy of tools/bridge.
val schemaDir: Provider<Directory> = providers.gradleProperty("qupflowr.schemaDir")
    .map { layout.projectDirectory.dir(file(it).absolutePath) }
    .orElse(layout.projectDirectory.dir("../../../inst/schema"))

/** Copies the shared error-code registry; fails with an explanation when it is missing. */
abstract class CopyErrorCodeRegistry : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val registry: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copy() {
        val source = registry.singleFile
        if (!source.isFile) {
            throw GradleException(
                "Protocol error-code registry not found: $source. It is the single source of " +
                    "truth shared with the R package (inst/schema/protocol/v1/error-codes.json). " +
                    "Build inside the repository or pass -Pqupflowr.schemaDir=<inst/schema dir>."
            )
        }
        val target = outputDirectory.get().asFile
            .resolve("io/github/cttir/qupflowr/protocol/v1/error-codes.json")
        target.parentFile.mkdirs()
        source.copyTo(target, overwrite = true)
    }
}

val copyErrorCodeRegistry by tasks.registering(CopyErrorCodeRegistry::class) {
    group = "build"
    description = "Copies inst/schema/protocol/v1/error-codes.json into the resources"
    registry.from(schemaDir.map { it.file("protocol/v1/error-codes.json") })
    outputDirectory = layout.buildDirectory.dir("generated/resources/error-codes")
}

val writeBuildInfo by tasks.registering {
    group = "build"
    description = "Writes bridge-build.properties with the bridge, protocol and QuPath versions"
    val out = layout.buildDirectory.dir("generated/resources/build-info")
    inputs.property("bridgeVersion", bridgeVersion)
    inputs.property("protocolVersion", protocolVersion)
    inputs.property("qupathVersion", qupathVersion)
    outputs.dir(out)
    doLast {
        val file = out.get().asFile
            .resolve("io/github/cttir/qupflowr/protocol/bridge-build.properties")
        file.parentFile.mkdirs()
        file.writeText(
            "bridge_version=$bridgeVersion\n" +
                "protocol_version=$protocolVersion\n" +
                "qupath_version=$qupathVersion\n"
        )
    }
}

sourceSets {
    main {
        resources.srcDir(copyErrorCodeRegistry)
        resources.srcDir(writeBuildInfo)
    }
}

tasks.processResources {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.test {
    systemProperty(
        "qupflowr.test.errorCodesSource",
        schemaDir.get().file("protocol/v1/error-codes.json").asFile.absolutePath,
    )
    systemProperty("qupflowr.test.schemaDir", schemaDir.get().asFile.absolutePath)
    // The schema validator and canonical JSON tests read the whole shared schema tree (the protocol
    // set with registry, index, fixtures and canonical vectors, and the annotatR capability
    // vocabulary), so all of it is a test input.
    inputs.dir(schemaDir).withPropertyName("sharedSchemaTree")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Cross-language canonical JSON parity: tools/bridge/scripts/contract-parity.sh passes the
    // corpus written by R with -Pqupflowr.parityDir=<dir> (and --rerun). Without the property the
    // test class is filtered out instead of being reported as skipped.
    val parityDir = providers.gradleProperty("qupflowr.parityDir").orNull
    if (parityDir != null) {
        systemProperty("qupflowr.test.parityDir", file(parityDir).absolutePath)
    } else {
        filter { excludeTestsMatching("*.CanonicalJsonRParityTest") }
    }
}
