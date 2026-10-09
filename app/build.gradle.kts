plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.galaxypulse"

    // The current AndroidX releases (core 1.19, lifecycle 2.11) require compiling against API 37.
    // targetSdk stays at 36: compileSdk only unlocks newer APIs, it does not change runtime behaviour.
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.galaxypulse"
        // Android 12 (API 31): BLUETOOTH_CONNECT/SCAN runtime permissions, createWindowContext(),
        // RenderEffect. Every Galaxy S phone from the S21 up ships with at least this.
        minSdk = 31

        @Suppress("OldTargetApi")
        targetSdk = 36

        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // A fixed, project-local debug key (password "android") so APKs produced by different
        // CI runs share one signature and install over each other. NOT a release key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Screenshot tests (Robolectric, native graphics) render the real overlay composables to PNGs for
    // review. They are opt-in (-Pscreenshots) so the default unit-test run stays fast and pure-JVM.
    val screenshots = project.hasProperty("screenshots")
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = screenshots
            all { test ->
                if (screenshots) test.include("**/screenshots/**") else test.exclude("**/screenshots/**")
            }
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/*.version",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        // Lint runs as its own CI step so a lint problem never blocks the APK artifact.
        abortOnError = true
        checkReleaseBuilds = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "OldTargetApi")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.android)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.android)
    implementation(libs.androidx.savedstate)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.lottie.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
