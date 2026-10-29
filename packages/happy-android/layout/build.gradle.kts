import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain JVM module, as happy-ios HappyLayout is pure Swift: every layout is a
// pure function from sizes to frames, tested on the host JVM.
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}
