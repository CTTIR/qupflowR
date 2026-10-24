// bridge-extension: the QuPath 0.7.0 extension (menu Extensions > qupflowR, live bridge lifecycle).
// The jar contains only qupflowR classes (protocol, core, extension) plus the service registration;
// QuPath, JavaFX, Gson and SLF4J come from the QuPath installation (see verifyJarContents).

plugins {
    id("qupath-conventions")
}

qupathExtension {
    name = "qupflowr-bridge-extension"
    group = "io.github.cttir.qupflowr"
    version = project.version.toString()
    description = "qupflowR live bridge: local authenticated HTTP API inside QuPath"
    automaticModule = "io.github.cttir.qupflowr.extension"
}

// JavaFX is only needed to compile; QuPath provides it at run time.
javafx {
    configuration = "compileOnly"
}

dependencies {
    implementation(project(":bridge-core"))

    // qupath-gui-fx brings qupath-core and qupath-core-processing through its API dependencies.
    compileOnly(libs.qupath.gui.fx)
    compileOnly(libs.slf4j)
    compileOnly(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.gson)
    testImplementation(libs.logback)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Merge the classes of our own project dependencies (never third-party jars) into the jar.
val bundledProjectJars: FileCollection = configurations.runtimeClasspath.get().incoming
    .artifactView { componentFilter { it is ProjectComponentIdentifier } }.files

tasks.jar {
    dependsOn(bundledProjectJars)
    from(bundledProjectJars.elements.map { jars -> jars.map { zipTree(it.asFile) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
}
