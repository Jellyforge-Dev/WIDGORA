import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// :core holds data handling, transport and integrations. It depends on :model only.

android {
    namespace = "io.github.jellyforgedev.widgora.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // Instrumented tests exist for the parts that cannot run without the platform: the
        // keystore and a real preference file. They are started by hand on a device or
        // emulator and are deliberately not part of the continuous integration run.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.jvmTarget.get())
    }

    testOptions {
        unitTests.all { test ->
            // Same test framework as :model, so assertions and annotations behave identically
            // in both modules.
            test.useJUnitPlatform()

            // The hand probe against a real installation reads these two variables and is
            // skipped when either is missing. They are forwarded explicitly because a test
            // process inherits the environment of the build daemon, which was captured when
            // that daemon started, not the environment of the shell running this build.
            // Nothing in this repository supplies a value.
            for (name in listOf("WIDGORA_KUMA_URL", "WIDGORA_KUMA_KEY")) {
                val supplied = providers.environmentVariable(name)
                if (supplied.isPresent) {
                    test.environment(name, supplied.get())
                }
            }
        }
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

    // The only module that talks to a network. :model, :bridge and :app must not gain this
    // dependency; the transport interface is what the rest of the project sees.
    implementation(libs.okhttp)

    // Storage stays an implementation detail: the repository exposes its own types, so no
    // consumer needs to know what it is stored in.
    implementation(libs.androidx.datastore.preferences)

    // Part of this module's own public API, because the repository hands out a Flow.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
