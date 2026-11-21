package rangetarget.core.image

import org.opencv.core.Core
import org.opencv.core.Mat

/**
 * A quarter-turn rotation to make a photo upright (card as the shooter faces
 * it), so card directions (+y up) mean what they say. Until the card finder can
 * work out orientation itself, the person running the tool supplies it.
 */
enum class Rotation(val cliName: String) {
    NONE("none"),
    /** Turn the picture 90° to the left (anticlockwise). */
    LEFT("left"),
    /** Turn the picture 90° to the right (clockwise). */
    RIGHT("right"),
    HALF("180");

    /** Returns a new, rotated image; the input is left untouched. */
    fun apply(image: Mat): Mat {
        val out = Mat()
        when (this) {
            NONE -> image.copyTo(out)
            LEFT -> Core.rotate(image, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            RIGHT -> Core.rotate(image, out, Core.ROTATE_90_CLOCKWISE)
            HALF -> Core.rotate(image, out, Core.ROTATE_180)
        }
        return out
    }

    companion object {
        fun fromCli(name: String): Rotation? = entries.firstOrNull { it.cliName == name.lowercase() }
    }
}
