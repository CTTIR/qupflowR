// bridge-core: java.base loopback HTTP transport, bridge server (auth, routing, discovery files)
// and the QuPath 0.7 adapter boundary. QuPath, Gson and SLF4J are compile-only (QuPath provides
// them at run time).

plugins {
    `java-library`
}

description = "qupflowR bridge core (loopback HTTP transport, bridge server, QuPath 0.7 adapter)"

dependencies {
    api(project(":bridge-protocol"))

    compileOnly(libs.gson)
    compileOnly(libs.slf4j)
    // Only QuPath07Adapter/GeneralToolsVersionProbe reference QuPath; tests run without it.
    compileOnly(libs.qupath.core)

    testImplementation(libs.gson)
    testImplementation(libs.logback)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}

tasks.test {
    // Location of the compiled transport classes for the java.base-only dependency test.
    systemProperty(
        "qupflowr.test.mainClassesDir",
        sourceSets.main.get().java.destinationDirectory.get().asFile.absolutePath,
    )
    // The DoS mitigation tests open many sockets; keep the suite predictable.
    maxParallelForks = 1
}
