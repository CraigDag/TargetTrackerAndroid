package rangetarget.core.debug

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import kotlin.math.roundToInt

/**
 * A simple placement of the card in an image: where the card centre is, how
 * many pixels make a millimetre, and no tilt. Enough for drawing until the
 * card finder (Phase 1) supplies a real homography.
 */
data class SimpleCardPlacement(val centreXPx: Double, val centreYPx: Double, val pxPerMm: Double) {
    /** Card mm (+y up) to image px (+y down). */
    fun toPx(p: PointMm): Point = Point(centreXPx + p.x * pxPerMm, centreYPx - p.y * pxPerMm)
}

/** Draws the ideal ISSF rings over an image, so you can see how a placement lines up. */
object RingOverlay {
    // OpenCV colours are BGR.
    val RING_COLOUR = Scalar(0.0, 220.0, 255.0)   // yellow
    val BLACK_EDGE_COLOUR = Scalar(255.0, 0.0, 255.0) // magenta
    val CENTRE_COLOUR = Scalar(0.0, 0.0, 255.0)   // red

    fun draw(image: Mat, placement: SimpleCardPlacement, label: String? = null) {
        val thickness = maxOf(1, (image.cols() / 800.0).roundToInt())
        val centre = placement.toPx(PointMm.ORIGIN)
        for (ring in CardGeometry.rings) {
            val r = CardGeometry.ringRadiusMm(ring) * placement.pxPerMm
            val colour = if (ring == CardGeometry.BLACK_OUTER_RING) BLACK_EDGE_COLOUR else RING_COLOUR
            val t = if (ring == CardGeometry.BLACK_OUTER_RING) thickness * 2 else thickness
            Imgproc.circle(image, centre, r.roundToInt(), colour, t, Imgproc.LINE_AA)
            // Ring number just inside the ring, on the right-hand side.
            val labelAt = placement.toPx(PointMm(CardGeometry.ringRadiusMm(ring) - 18.0, 0.0))
            if (ring < CardGeometry.HIGHEST_RING) {
                Imgproc.putText(image, ring.toString(), labelAt, Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.4 * thickness, colour, thickness, Imgproc.LINE_AA)
            }
        }
        val arm = 10.0 * placement.pxPerMm
        Imgproc.line(image, Point(centre.x - arm, centre.y), Point(centre.x + arm, centre.y), CENTRE_COLOUR, thickness)
        Imgproc.line(image, Point(centre.x, centre.y - arm), Point(centre.x, centre.y + arm), CENTRE_COLOUR, thickness)
        if (label != null) {
            Imgproc.putText(image, label, Point(20.0, 40.0 * thickness), Imgproc.FONT_HERSHEY_SIMPLEX,
                0.8 * thickness, CENTRE_COLOUR, thickness * 2, Imgproc.LINE_AA)
        }
    }

    /**
     * A placement that centres the card in the image and fits the 1 ring
     * inside 90% of the shorter side. Not a detection; used by the smoke test.
     */
    fun centredGuess(widthPx: Int, heightPx: Int): SimpleCardPlacement {
        val pxPerMm = 0.9 * minOf(widthPx, heightPx) / CardGeometry.outerRingDiameterMm
        return SimpleCardPlacement(widthPx / 2.0, heightPx / 2.0, pxPerMm)
    }
}
