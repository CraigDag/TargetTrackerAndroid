package rangetarget.core.card

import kotlinx.serialization.Serializable
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.CardGeometry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** An ellipse in photo pixels. [width]/[height] are full axis lengths; [angleDeg] as OpenCV's RotatedRect. */
@Serializable
data class EllipsePx(val cx: Double, val cy: Double, val width: Double, val height: Double, val angleDeg: Double) {
    /** How far [p] is from the centre, as a fraction of the ellipse radius in that direction (1 = on the edge). */
    fun normalisedRadius(p: Point): Double {
        val t = Math.toRadians(angleDeg)
        val dx = p.x - cx; val dy = p.y - cy
        val u = dx * cos(t) + dy * sin(t); val v = -dx * sin(t) + dy * cos(t)
        return sqrt((u / (width / 2)).let { it * it } + (v / (height / 2)).let { it * it })
    }

    fun pointAt(theta: Double): Point {
        val t = Math.toRadians(angleDeg)
        val u = width / 2 * cos(theta); val v = height / 2 * sin(theta)
        return Point(cx + u * cos(t) - v * sin(t), cy + u * sin(t) + v * cos(t))
    }

    /**
     * The map from card mm to pixels that turns the black disc (a 200 mm
     * circle) into this ellipse, with no extra turn. A first guess only:
     * rotation is fixed later from the paper edges, perspective from the rings.
     */
    fun toHomography(discRadiusMm: Double): Homography {
        val t = Math.toRadians(angleDeg)
        val a = width / 2 / discRadiusMm; val b = height / 2 / discRadiusMm
        val c = cos(t); val s = sin(t)
        // A = R diag(a,b) R^T, in image coordinates (y down).
        val a00 = a * c * c + b * s * s
        val a01 = (a - b) * c * s
        val a11 = a * s * s + b * c * c
        // Card +y is image -y.
        return Homography(listOf(a00, -a01, cx, a01, -a11, cy, 0.0, 0.0, 1.0))
    }
}

/** Result of looking for the black aiming disc. */
class BlackDiscResult(
    val ellipse: EllipsePx,
    /** Edge points of the dark region; [used] marks those that fit the ellipse (not cut by holes). */
    val edgePoints: List<Point>,
    val used: List<Boolean>,
    /** The dark-region mask that was searched (for the debug image). */
    val mask: Mat,
    /** Fraction of edge points that lie on the fitted ellipse. */
    val fitFraction: Double,
)

/**
 * Finds the black aiming disc: the darkest large, round region in the photo.
 * It gives the first estimate of the card's centre and scale (docs/SPEC.md,
 * Image processing step 1). Holes punched through the disc edge make notches,
 * so the ellipse is fitted repeatedly, dropping edge points that sit inside it.
 */
object BlackDiscFinder {
    fun find(bgr: Mat): BlackDiscResult? {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        // Threshold halfway between the darkest print and the typical (paper) level.
        val sample = rangetarget.core.image.Plane.of(gray)
        val hist = IntArray(256).also { h -> for (b in sample.data) h[b.toInt() and 0xFF]++ }
        val p1 = percentile(hist, sample.data.size, 0.01)
        val p50 = percentile(hist, sample.data.size, 0.50)
        val level = ((p1 + p50) / 2.0).coerceIn(40.0, 120.0)

        val mask = Mat()
        Imgproc.threshold(gray, mask, level, 255.0, Imgproc.THRESH_BINARY_INV)
        gray.release()
        // Drop thin dark lines first (printed ring lines would otherwise join the
        // disc to a dark backing round the card), then close the thin light ring
        // lines and numbers printed inside the black.
        val o = max(3, (min(bgr.cols(), bgr.rows()) * 0.004).roundToInt()) or 1
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(o.toDouble(), o.toDouble())))
        val k = max(5, (min(bgr.cols(), bgr.rows()) * 0.012).roundToInt()) or 1
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)

        // Outer edges at every depth: with a dark backing all round the card, the
        // disc sits inside a hole of the backing's region.
        val all = ArrayList<MatOfPoint>(); val hier = Mat()
        Imgproc.findContours(mask.clone(), all, hier, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_NONE)
        val hv = IntArray(all.size * 4).also { if (all.isNotEmpty()) hier.get(0, 0, it) }
        hier.release()
        val contours = all.filterIndexed { i, _ -> hv[i * 4 + 3] < 0 }
        val minArea = 0.005 * bgr.cols() * bgr.rows()
        // Pick the largest blob that is reasonably round and doesn't run off the
        // photo (a dark backing round the card can be darker than the threshold).
        val best = contours.filter { Imgproc.contourArea(it) > minArea }
            .filter { c -> val r = Imgproc.boundingRect(c); r.x > 0 && r.y > 0 && r.x + r.width < bgr.cols() && r.y + r.height < bgr.rows() }
            .map { c ->
                val area = Imgproc.contourArea(c)
                val centre = Point(); val r = FloatArray(1)
                Imgproc.minEnclosingCircle(MatOfPoint2f(*c.toArray()), centre, r)
                Triple(c, area, area / (PI * r[0] * r[0]))
            }
            .filter { it.third > 0.72 }
            .maxByOrNull { it.second } ?: return null

        val pts = best.first.toArray().map { Point(it.x, it.y) }
        var used = List(pts.size) { true }
        var ellipse: EllipsePx? = null
        for (tolerance in listOf(0.06, 0.03, 0.015, 0.01)) {
            val chosen = pts.filterIndexed { i, _ -> used[i] }
            if (chosen.size < 20) break
            val rr = Imgproc.fitEllipse(MatOfPoint2f(*chosen.toTypedArray()))
            val e = EllipsePx(rr.center.x, rr.center.y, rr.size.width, rr.size.height, rr.angle)
            ellipse = e
            // Notches from holes lie inside the ellipse; keep points close to the edge.
            used = pts.map { abs(e.normalisedRadius(it) - 1.0) < tolerance }
        }
        val e = ellipse ?: return null
        return BlackDiscResult(e, pts, used, mask, used.count { it }.toDouble() / pts.size)
    }

    /** Debug image: the dark mask in blue, edge points kept (green) or dropped (red), and the fitted ellipse. */
    fun drawDebug(bgr: Mat, r: BlackDiscResult): Mat {
        val out = bgr.clone()
        val tint = Mat(out.size(), out.type(), org.opencv.core.Scalar(255.0, 120.0, 0.0))
        val blended = Mat()
        Core.addWeighted(out, 0.6, tint, 0.4, 0.0, blended)
        blended.copyTo(out, r.mask)
        tint.release(); blended.release()
        val t = max(1, (out.cols() / 800.0).roundToInt())
        r.edgePoints.forEachIndexed { i, p ->
            Imgproc.circle(out, p, t, if (r.used[i]) GREEN else RED, -1)
        }
        val e = r.ellipse
        Imgproc.ellipse(out, Point(e.cx, e.cy), Size(e.width / 2, e.height / 2), e.angleDeg, 0.0, 360.0, YELLOW, t)
        Imgproc.drawMarker(out, Point(e.cx, e.cy), YELLOW, Imgproc.MARKER_CROSS, 30 * t, t)
        val scale = (e.width + e.height) / 2 / CardGeometry.blackDiameterMm
        return captioned(out, "Step 1: black disc. Green = edge used, red = edge cut by holes (ignored)", "Ellipse %.0f x %.0f px, about %.2f px/mm; %.0f%% of edge used".format(
            e.width, e.height, scale, 100 * r.fitFraction))
    }

    private fun percentile(hist: IntArray, n: Int, q: Double): Double {
        var acc = 0; val target = q * n
        for (i in hist.indices) { acc += hist[i]; if (acc >= target) return i.toDouble() }
        return 255.0
    }
}

internal val GREEN = org.opencv.core.Scalar(0.0, 200.0, 0.0)
internal val RED = org.opencv.core.Scalar(0.0, 0.0, 255.0)
internal val YELLOW = org.opencv.core.Scalar(0.0, 220.0, 255.0)
internal val CYAN = org.opencv.core.Scalar(255.0, 220.0, 0.0)
internal val MAGENTA = org.opencv.core.Scalar(255.0, 0.0, 255.0)

/**
 * Returns [img] with a dark band added on top holding [lines] of caption, so
 * captions never cover the picture. [img] is released.
 */
internal fun captioned(img: Mat, vararg lines: String): Mat {
    val s = max(0.6, img.cols() / 2000.0)
    val th = max(1, (2 * s).roundToInt())
    val lineH = (38 * s).roundToInt()
    val band = lineH * lines.size + (14 * s).roundToInt()
    val out = Mat()
    Core.copyMakeBorder(img, out, band, 0, 0, 0, Core.BORDER_CONSTANT, org.opencv.core.Scalar(30.0, 30.0, 30.0))
    img.release()
    lines.forEachIndexed { i, text ->
        Imgproc.putText(out, text, Point(15.0, (i + 1) * lineH.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, s,
            org.opencv.core.Scalar(255.0, 255.0, 255.0), th, Imgproc.LINE_AA)
    }
    return out
}
