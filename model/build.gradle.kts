import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :model is pure Kotlin/JVM and must never gain an Android dependency.
// It is testable without a device or an emulator, which is why the logic worth
// testing belongs here.

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.jdkToolchain.get().toInt())
    }
    // The toolchain compiles the code; the bytecode level stays lower so that the
    // same classes remain usable from the Android modules.
    sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
