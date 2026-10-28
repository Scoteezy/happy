import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain JVM module: happy-app's icon glyphs as vector outlines (happy-ios
// HappyIcons), decoded without Android so they are tested on the host JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(libs.versions.java.toolchain.get().toInt())
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":layout"))
    testImplementation(libs.junit)
}
