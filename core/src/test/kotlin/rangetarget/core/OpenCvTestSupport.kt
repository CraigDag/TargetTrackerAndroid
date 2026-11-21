package rangetarget.core

import java.io.File

/** Loads the desktop OpenCV natives for core's tests (core itself never loads natives). */
object OpenCvTestSupport {
    private var loaded = false

    @Synchronized
    fun load() {
        if (!loaded) { nu.pattern.OpenCV.loadLocally(); loaded = true }
    }

    val repoRoot: File get() = File(System.getProperty("rangetarget.repoRoot") ?: "..")
}
