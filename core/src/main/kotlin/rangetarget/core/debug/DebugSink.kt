package rangetarget.core.debug

import org.opencv.core.Mat

/**
 * Where pipeline stages put their annotated debug images. Each platform
 * supplies one: the desktop tool writes files, the phone app will show them on
 * the confirm screen. Stages hand over a [Mat] they no longer need.
 */
fun interface DebugSink {
    fun save(name: String, image: Mat)

    companion object {
        /** Discards everything (for tests and when debug output isn't wanted). */
        val NONE = DebugSink { _, image -> image.release() }
    }
}
