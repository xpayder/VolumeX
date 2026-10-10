plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fatalpuppet.volumex"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.feldkit"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // libVLC ships native libraries for every ABI (80 MB); phones that matter here are arm64
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.systemProperty("fixtures.dir", layout.buildDirectory.dir("fixtures").get().asFile.path)
                it.maxHeapSize = "2g"
                if (project.hasProperty("vxlog")) it.systemProperty("vx.log", "1")
                it.testLogging.showStandardStreams = project.hasProperty("vxlog")
                it.testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("dev.chrisbanes.haze:haze:2.0.1")
    implementation("dev.chrisbanes.haze:haze-blur:2.0.1")
    implementation("dev.chrisbanes.haze:haze-glass:2.0.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}