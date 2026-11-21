package rangetarget.core.image

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.floor

/**
 * One 8-bit image plane copied out of OpenCV into a Kotlin array, for the
 * per-pixel work (profile sampling, colour rules) that is clumsy through the
 * OpenCV Java API.
 */
class Plane(val width: Int, val height: Int, val data: ByteArray) {
    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF

    fun inside(x: Double, y: Double): Boolean = x >= 0 && y >= 0 && x <= width - 1.001 && y <= height - 1.001

    /** Bilinear sample, or NaN outside the image. */
    fun sample(x: Double, y: Double): Double {
        if (!inside(x, y)) return Double.NaN
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = x - x0; val fy = y - y0
        val a = get(x0, y0); val b = get(x0 + 1, y0)
        val c = get(x0, y0 + 1); val d = get(x0 + 1, y0 + 1)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    fun toMat(): Mat = Mat(height, width, CvType.CV_8UC1).also { it.put(0, 0, data) }

    companion object {
        fun of(mat: Mat): Plane {
            require(mat.type() == CvType.CV_8UC1) { "Expected an 8-bit single-channel image" }
            val m = if (mat.isContinuous) mat else mat.clone()
            val buf = ByteArray(m.rows() * m.cols())
            m.get(0, 0, buf)
            return Plane(m.cols(), m.rows(), buf)
        }

        fun gray(bgr: Mat): Plane {
            val g = Mat()
            Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY)
            return of(g).also { g.release() }
        }
    }
}

/** Hue (0..179, OpenCV units), saturation and value planes of a BGR image. */
class HsvPlanes(val hue: Plane, val sat: Plane, val value: Plane) {
    companion object {
        fun of(bgr: Mat): HsvPlanes {
            val hsv = Mat()
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            val parts = ArrayList<Mat>()
            org.opencv.core.Core.split(hsv, parts)
            val p = HsvPlanes(Plane.of(parts[0]), Plane.of(parts[1]), Plane.of(parts[2]))
            parts.forEach { it.release() }; hsv.release()
            return p
        }
    }
}
