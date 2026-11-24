import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The phone app (docs/SPEC.md, "App v1"). Screens, camera and storage only:
// all analysis is the :core module, used unchanged.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.rangetarget"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.rangetarget"
        minSdk = 26
        targetSdk = 35
        // CI sets BUILD_NUMBER so each test build installs over the last one.
        versionCode = (System.getenv("BUILD_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("BUILD_NUMBER") ?: "0")
        // The target phone is 64-bit ARM; leaving out other CPUs keeps the APK small.
        // The emulator check in CI builds for x86_64 instead (-Pabis=x86_64).
        ndk { abiFilters += ((findProperty("abis") as String?) ?: "arm64-v8a").split(",") }
    }

    // Test builds are signed with a fixed, throwaway test key checked into the
    // repository, so a new build installs over the old one without losing data.
    // It is NOT the key for Google Play; that one will be kept out of the repo.
    signingConfigs {
        create("sideload") {
            storeFile = file("test-signing/sideload-test.keystore")
            storePassword = "android"
            keyAlias = "sideload"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("sideload") }
        getByName("release") {
            signingConfig = signingConfigs.getByName("sideload")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = false } }
    // A sample session (synthetic photos from test_data) so the app can be tried away from the range.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("demo-assets"))
}

val demoAssets by tasks.registering(Sync::class) {
    from(rootProject.file("test_data/synthetic/realistic_sticker_s0202")) { include("baseline.jpg", "round_01.jpg", "round_02.jpg") }
    into(layout.buildDirectory.dir("demo-assets/demo"))
}
tasks.named("preBuild") { dependsOn(demoAssets) }

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core"))
    implementation(libs.opencv.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.exifinterface)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
}
