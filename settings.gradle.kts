plugins {
    // Lets Gradle download JDK 21 for the build when the machine doesn't have one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ClaudeChat"
