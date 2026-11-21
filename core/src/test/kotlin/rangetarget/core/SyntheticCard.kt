package rangetarget.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.card.Homography
import rangetarget.core.card.TopDown
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A quick, deterministic test card drawn in Kotlin for the unit tests: beige
 * paper on a grey floor, printed rings, the black disc, holes and some blue
 * pen. Much simpler than the Phase 2 generator, but enough to check each stage
 * against known answers.
 */
object SyntheticCard {
    val FLOOR = Scalar(139.0, 132.0, 129.0)
    val BEIGE = Scalar(177.0, 197.0, 214.0)
    val INK = Scalar(55.0, 52.0, 52.0)
    val BLACK = Scalar(26.0, 22.0, 24.0)
    val HOLE_ON_BEIGE = Scalar(105.0, 98.0, 95.0)
    val HOLE_IN_BLACK = Scalar(140.0, 133.0, 129.0)
    val PEN = Scalar(150.0, 70.0, 40.0)

    const val PPM = 4.0
    private const val HALF = 330.0

    /** Draws the card top-down in mm at [PPM] px/mm, covering ±[HALF] mm. */
    fun flat(holes: List<PointMm> = emptyList(), holeDiameterMm: Double = 8.0, pen: Boolean = true): TopDown {
        val size = (2 * HALF * PPM).roundToInt()
        val img = Mat(size, size, CvType.CV_8UC3, FLOOR)
        val v = TopDown(img, PPM, -HALF, HALF)
        val w = CardGeometry.PAPER_WIDTH_MM / 2; val h = CardGeometry.PAPER_HEIGHT_MM / 2
        Imgproc.rectangle(img, v.toPx(PointMm(-w, h)), v.toPx(PointMm(w, -h)), BEIGE, -1)
        val c = v.toPx(PointMm.ORIGIN)
        for (ring in 1..6) circle(img, c, CardGeometry.ringRadiusMm(ring), INK, 0.4)
        Imgproc.circle(img, c, (CardGeometry.blackRadiusMm * PPM).roundToInt(), BLACK, -1, Imgproc.LINE_AA)
        for (ring in 8..10) circle(img, c, CardGeometry.ringRadiusMm(ring), BEIGE, 0.5)
        if (pen) {
            // Loops and scribbles in blue ballpoint, including one crossing a ring line.
            for (k in 0 until 5) {
                val cx = -200.0 + k * 90; val cy = -150.0 + (k % 2) * 300
                Imgproc.ellipse(img, v.toPx(PointMm(cx, cy)), Size(25 * PPM, 18 * PPM), k * 20.0, 0.0, 330.0, PEN, (0.5 * PPM).roundToInt(), Imgproc.LINE_AA)
            }
            Imgproc.line(img, v.toPx(PointMm(-240.0, 40.0)), v.toPx(PointMm(-120.0, 90.0)), PEN, 2, Imgproc.LINE_AA)
        }
        for (p in holes) {
            val col = if (p.radius < CardGeometry.blackRadiusMm) HOLE_IN_BLACK else HOLE_ON_BEIGE
            Imgproc.circle(img, v.toPx(p), (holeDiameterMm / 2 * PPM).roundToInt(), col, -1, Imgproc.LINE_AA)
        }
        return v
    }

    private fun circle(img: Mat, c: Point, rMm: Double, col: Scalar, widthMm: Double) {
        Imgproc.circle(img, c, (rMm * PPM).roundToInt(), col, maxOf(1, (widthMm * PPM).roundToInt()), Imgproc.LINE_AA)
    }

    /** A camera-ish map from card mm to photo pixels: tilt, a small turn, ~2.7 px/mm. */
    fun camera(turnDeg: Double = 2.0, tilt: Double = 0.00012): Homography {
        val t = Math.toRadians(turnDeg)
        val s = 2.7
        return Homography(listOf(
            s * cos(t), s * sin(t), 850.0,
            s * sin(t), -s * cos(t), 800.0,
            tilt, 0.00005, 1.0,
        ))
    }

    /** Photographs [flat] through [cardToPhoto] onto a floor-coloured frame, with a little blur and noise. */
    fun photo(flat: TopDown, cardToPhoto: Homography, width: Int = 1700, height: Int = 1600, seed: Long = 1): Mat {
        // Map from photo pixels back to flat-view pixels.
        val back = flat.toImage * cardToPhoto.inverse()
        val out = Mat()
        Imgproc.warpPerspective(flat.image, out, back.toMat(), Size(width.toDouble(), height.toDouble()),
            Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_CONSTANT, FLOOR)
        Imgproc.GaussianBlur(out, out, Size(3.0, 3.0), 0.0)
        val noise = Mat(out.size(), out.type())
        Core.setRNGSeed(seed.toInt())
        Core.randn(noise, 0.0, 3.0)
        Core.add(out, noise, out)
        noise.release()
        return out
    }

    /** Hole positions spread over the beige and the black, none overlapping, none on ring lines exactly. */
    fun scatteredHoles(): List<PointMm> = buildList {
        for (k in 0 until 12) { val a = k * 2 * PI / 12 + 0.2; add(PointMm(160 * cos(a), 160 * sin(a))) }
        for (k in 0 until 8) { val a = k * 2 * PI / 8 + 0.4; add(PointMm(55 * cos(a), 55 * sin(a))) }
        add(PointMm(12.0, -8.0))
        add(PointMm(-230.0, 210.0))
    }

}
