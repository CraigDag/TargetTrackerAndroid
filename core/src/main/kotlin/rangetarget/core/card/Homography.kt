package rangetarget.core.card

import kotlinx.serialization.Serializable
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import rangetarget.core.geometry.PointMm
import kotlin.math.cos
import kotlin.math.sin

/**
 * A projective map from the card frame (mm, +y up) to photo pixels (+y down),
 * stored as a row-major 3x3 matrix. Points are mapped in plain Kotlin so the
 * analysis doesn't need an OpenCV Mat for every coordinate.
 */
@Serializable
data class Homography(val m: List<Double>) : CardToPhoto {
    init { require(m.size == 9) { "A homography has 9 entries" } }

    override fun toMm(px: Point): PointMm = inverted.map(px.x, px.y).let { PointMm(it.first, it.second) }

    private val inverted: Homography by lazy { inverse() }

    override fun map(x: Double, y: Double): Pair<Double, Double> {
        val w = m[6] * x + m[7] * y + m[8]
        return Pair((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    /** this ∘ other: first apply [other], then this. */
    operator fun times(other: Homography): Homography {
        val a = m; val b = other.m
        return Homography(List(9) { i ->
            val r = i / 3; val c = i % 3
            a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
        }).normalised()
    }

    fun inverse(): Homography {
        val (a, b, c, d, e, f, g, h, i) = m
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(kotlin.math.abs(det) > 1e-18) { "Homography is singular" }
        return Homography(listOf(
            (e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det,
            (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
            (d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det,
        )).normalised()
    }

    fun normalised(): Homography = if (m[8] == 0.0) this else Homography(m.map { it / m[8] })

    fun toMat(): Mat = Mat(3, 3, CvType.CV_64F).also { it.put(0, 0, *m.toDoubleArray()) }

    companion object {
        fun fromMat(mat: Mat): Homography {
            require(mat.rows() == 3 && mat.cols() == 3) { "Expected a 3x3 matrix" }
            val d = DoubleArray(9)
            val m64 = Mat().also { mat.convertTo(it, CvType.CV_64F) }
            m64.get(0, 0, d)
            m64.release()
            return Homography(d.toList()).normalised()
        }

        val IDENTITY = Homography(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))

        /** Turns the card frame by [degrees] anticlockwise (as the shooter sees it). */
        fun rotationMm(degrees: Double): Homography {
            val t = Math.toRadians(degrees)
            return Homography(listOf(cos(t), -sin(t), 0.0, sin(t), cos(t), 0.0, 0.0, 0.0, 1.0))
        }

        /** Maps card mm to a top-down image with [pxPerMm] whose top-left corner is card point ([left], [top]). */
        fun mmToTopDown(pxPerMm: Double, left: Double, top: Double): Homography =
            Homography(listOf(pxPerMm, 0.0, -left * pxPerMm, 0.0, -pxPerMm, top * pxPerMm, 0.0, 0.0, 1.0))
    }
}

private operator fun <T> List<T>.component6() = this[5]
private operator fun <T> List<T>.component7() = this[6]
private operator fun <T> List<T>.component8() = this[7]
private operator fun <T> List<T>.component9() = this[8]
