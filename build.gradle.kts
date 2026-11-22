buildscript {
    // The Android build plugin is only loaded when the phone app is part of the
    // build (see settings.gradle.kts), so core and desktop build without it.
    if (java.io.File(rootDir, "android/build.gradle.kts").exists() &&
        (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
            java.io.File(rootDir, "local.properties").let { it.exists() && it.readText().contains("sdk.dir") })) {
        repositories { google(); mavenCentral() }
        dependencies { classpath(libs.android.gradle.plugin) }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
