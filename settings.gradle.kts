rootProject.name = "rangetarget"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google { content { includeGroupByRegex("com\\.android.*|androidx.*|com\\.google.*") } }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google { content { includeGroupByRegex("com\\.android.*|androidx.*|com\\.google.*") } }
    }
}

include(":core", ":desktop")

// The phone app needs the Android SDK. It is built wherever one is installed
// (Android Studio, the GitHub build); elsewhere core and desktop still build.
if (AndroidSdk.present(settingsDir)) include(":android")

object AndroidSdk {
    fun present(root: File): Boolean =
        System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null ||
            File(root, "local.properties").takeIf { it.exists() }?.readText()?.contains("sdk.dir") == true
}
