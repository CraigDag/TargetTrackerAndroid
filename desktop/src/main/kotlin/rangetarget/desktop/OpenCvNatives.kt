package rangetarget.desktop

import org.opencv.core.Core

/** Loads the OpenCV native library bundled in the openpnp artifact. Safe to call more than once. */
object OpenCvNatives {
    @Volatile private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        nu.pattern.OpenCV.loadLocally()
        loaded = true
    }

    fun version(): String {
        load()
        return Core.VERSION
    }
}
