import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// :bridge is a security boundary. It depends on :model and must never be able to reach
// :core or any integration, so that no integration detail can reach the data a foreign
// application is able to read.

android {
    namespace = "io.github.jellyforgedev.widgora.bridge"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }
}

kotlin {
    // Android Gradle plugin 9 provides Kotlin support itself, so no Kotlin plugin is
    // applied here. The toolchain compiles the code; the bytecode level is stated
    // explicitly so that it matches compileOptions above and :model.
    jvmToolchain(libs.versions.jdkToolchain.get().toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.jvmTarget.get())
    }
}

dependencies {
    implementation(project(":model"))
}
