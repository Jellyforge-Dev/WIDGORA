import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jellyforgedev.widgora"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.jellyforgedev.widgora"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"

        // The widget's pending intent can only be examined where there is a real package manager
        // behind it. That test is started by hand against a device and is deliberately not part
        // of the continuous integration run, exactly as the ones in :core are.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // No signing configuration is declared. Release signing is set up only once a
    // keystore is held outside version control.

    buildFeatures {
        compose = true

        // Needed for one decision: whether the settings screen offers the widget host. The host
        // itself lives in the debug source set, so a release build contains neither it nor the
        // permission it needs; this flag only decides whether an entry to it is drawn.
        buildConfig = true
    }

    testOptions {
        unitTests.all { test ->
            // Same test framework as the other modules, so assertions behave identically.
            test.useJUnitPlatform()
        }
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
    implementation(project(":core"))
    implementation(project(":bridge"))

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)

    // Text input: androidx.tv:tv-material 1.1.0 ships no text field, so the basic one from
    // Compose Foundation is used. The library is already in the tree through tv-material; it is
    // declared here because this module uses it directly.
    implementation(libs.androidx.compose.foundation)

    // Used directly for the dispatcher of the connection test and for the state flow of the
    // settings screen, rather than relied upon through another module.
    implementation(libs.kotlinx.coroutines.core)

    // The annotation for a preview ships in the release build; the renderer behind it does not.
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.kotlin.test.junit5)

    // The same two artefacts :core already uses for its instrumented tests. Nothing new enters
    // the project; they only become available to this module as well.
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)

    // Compose from the device's point of view: a test can put a screen on a real display and send
    // it the keys a remote control sends. It is the only way to find out whether the focus really
    // moves from a field to the button beside it, which is a question no unit test can answer.
    // The bill of materials is repeated because this configuration resolves on its own and would
    // otherwise not know which Compose version to take.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
