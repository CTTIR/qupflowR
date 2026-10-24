// qupflowR Java bridge: protocol DTOs, loopback HTTP core, QuPath extension and headless worker.
//
// Build from this directory with the pinned wrapper (Gradle 9.4.0, checksum in
// gradle/wrapper/gradle-wrapper.properties):
//   ./gradlew test bridgeBundle verifyJarContents
// Dependency locks (gradle.lockfile, settings-gradle.lockfile) and gradle/verification-metadata.xml
// are committed; README.md explains how to refresh them after a deliberate dependency change.

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.scijava.org/content/repositories/releases")
        }
    }
}

buildscript {
    // Lock the settings classpath (the QuPath settings plugin and its transitive plugins).
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

plugins {
    // Provides the QuPath version catalogue "libs" and the "qupath-conventions" project plugin.
    id("io.github.qupath.qupath-extension-settings") version "0.2.1"
}

qupath {
    version = "0.7.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "qupflowr-bridge"

include("bridge-protocol")
include("bridge-core")
include("bridge-extension")
include("bridge-worker")
include("bridge-fixtures")
