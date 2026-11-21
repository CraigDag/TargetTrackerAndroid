package rangetarget.core.card

import kotlinx.serialization.Serializable
import org.opencv.calib3d.Calib3d
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import rangetarget.core.image.Plane
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One place where a printed ring was seen: the ideal ring point and where the line actually is. */
data class RingSample(val ring: Int, val angleRad: Double, val foundRadiusMm: Double, val foundPx: Point)

/** How well the ideal rings sit on the printed ones, per ring, in mm. */
@Serializable
data class RingResidual(
    val ring: Int,
    val samples: Int,
    /** Null when no point on this ring was usable (e.g. the ring runs off the photo). */
    val medianMm: Double?,
    val p95Mm: Double?,
    /** Median of (found radius − ideal radius): positive means the printed line sits outside the ideal ring. */
    val signedMedianMm: Double?,
)

data class RingFitResult(
    val map: CardMap,
    val samples: List<RingSample>,
    val inlier: List<Boolean>,
    /** Distance of each sample from its ideal ring after the final fit, in mm. */
    val errorMm: List<Double>,
    val perRing: List<RingResidual>,
) {
    val inlierCount get() = inlier.count { it }
    val medianErrorMm get() = errorMm.filterIndexed { i, _ -> inlier[i] }.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
    val p95ErrorMm get() = errorMm.filterIndexed { i, _ -> inlier[i] }.sorted().let { if (it.isEmpty()) Double.NaN else it[(it.size * 0.95).toInt().coerceAtMost(it.size - 1)] }
}

/**
 * Refines the card-to-photo map so the ideal rings land on the printed ring
 * lines. Starting from a rough map (the black disc), it looks along each ring
 * at many angles for the actual line (dark on beige for rings 1–6, the black
 * edge for ring 7, light lines inside the black for rings 8–10), then fits a
 * perspective map to everything found, ignoring points that don't agree (holes,
 * pen, numbers). It repeats with a narrower search each time.
 *
 * Rings are round, so they cannot tell which way is up: the turn of the card
 * is left as it came in and fixed afterwards from the paper edges.
 */
object RingFitter {
    private const val ANGLES = 720
    /** Skip this many degrees either side of the four axes, where the ring numbers are printed. */
    private const val NUMBER_GAP_DEG = 7.0
    private const val STEP_MM = 0.1

    fun fit(gray: Plane, start: Homography, exclude: (PointMm) -> Boolean = { false }, bend: Boolean = true): RingFitResult {
        var h = start
        var fitted = false
        // Stage 1, flat paper: a perspective map. Start near the black disc, where
        // the first guess is best, then widen to all rings.
        for ((window, outermost) in listOf(5.0 to 5, 5.0 to 3, 4.0 to 1, 3.0 to 1, 2.0 to 1, 2.0 to 1)) {
            val samples = findSamples(gray, h, window, exclude, outermost)
            if (samples.size < 40) break
            val pxPerMm = h.pxPerMmAt()
            val src = MatOfPoint2f(*samples.map { idealPoint(it) }.toTypedArray())
            val dst = MatOfPoint2f(*samples.map { it.foundPx }.toTypedArray())
            val inlierMask = Mat()
            val hm = Calib3d.findHomography(src, dst, Calib3d.RANSAC, max(1.0, 1.0 * pxPerMm), inlierMask, 4000, 0.999)
            if (hm.empty()) break
            val flags = ByteArray(samples.size).also { inlierMask.get(0, 0, it) }
            val ins = samples.filterIndexed { i, _ -> flags[i].toInt() != 0 }
            // Least-squares polish on the inliers only.
            val polished = Calib3d.findHomography(
                MatOfPoint2f(*ins.map { idealPoint(it) }.toTypedArray()),
                MatOfPoint2f(*ins.map { it.foundPx }.toTypedArray()), 0)
            h = Homography.fromMat(if (polished.empty()) hm else polished)
            fitted = true
        }
        if (!fitted) return RingFitResult(CardMap(start), emptyList(), emptyList(), emptyList(), emptyList())

        // Stage 2, gentle bend: paper on a floor is rarely perfectly flat, and lenses
        // bend straight lines a little. Fit a smooth bend on top of the perspective
        // map, dropping points that disagree, with a tighter tolerance each round.
        var map = CardMap(h)
        val pxPerMm = h.pxPerMmAt()
        var samples = findSamples(gray, map, 2.5, exclude, 1)
        if (bend) for (tolMm in listOf(2.5, 1.5, 1.0, 0.8)) {
            val keep = samples.filter { s -> distPx(map, s) < tolMm * pxPerMm }
            map = CardMap.fitBend(h, keep.map { PointMm(idealPoint(it).x, idealPoint(it).y) }, keep.map { it.foundPx })
            samples = findSamples(gray, map, 2.0, exclude, 1)
        }
        val inl = samples.map { distPx(map, it) < 1.0 * pxPerMm }
        val signed = samples.map { s -> map.toMm(s.foundPx).radius - CardGeometry.ringRadiusMm(s.ring) }
        val err = signed.map { abs(it) }
        val perRing = CardGeometry.rings.map { ring ->
            val idx = samples.indices.filter { samples[it].ring == ring && inl[it] }
            val e = idx.map { err[it] }.sorted()
            val sg = idx.map { signed[it] }.sorted()
            RingResidual(ring, e.size,
                e.getOrNull(e.size / 2),
                e.getOrNull((e.size * 0.95).toInt().coerceAtMost(e.size - 1)),
                sg.getOrNull(sg.size / 2))
        }
        return RingFitResult(map, samples, inl, err, perRing)
    }

    private fun distPx(map: CardMap, s: RingSample): Double {
        val p = idealPoint(s); val (u, v) = map.map(p.x, p.y)
        return kotlin.math.hypot(u - s.foundPx.x, v - s.foundPx.y)
    }

    private fun idealPoint(s: RingSample): Point {
        val r = CardGeometry.ringRadiusMm(s.ring)
        return Point(r * cos(s.angleRad), r * sin(s.angleRad))
    }

    /** Looks across each ring at [ANGLES] angles, within ±[window] mm, for the printed line. */
    fun findSamples(gray: Plane, h: CardToPhoto, window: Double, exclude: (PointMm) -> Boolean, outermost: Int = 1): List<RingSample> {
        val out = ArrayList<RingSample>()
        val profile = DoubleArray((2 * window / STEP_MM).roundToInt() + 2)
        for (ring in outermost..CardGeometry.HIGHEST_RING) {
            val r0 = CardGeometry.ringRadiusMm(ring)
            val kind = when {
                ring == CardGeometry.BLACK_OUTER_RING -> Kind.EDGE
                ring > CardGeometry.BLACK_OUTER_RING -> Kind.LIGHT_LINE
                else -> Kind.DARK_LINE
            }
            // Inner rings are close together; don't let the search reach a neighbour.
            val w = minOf(window, CardGeometry.RING_DIAMETER_STEP_MM / 2 * 0.4)
            val m = (2 * w / STEP_MM).roundToInt() + 1
            for (k in 0 until ANGLES) {
                val theta = 2 * PI * k / ANGLES
                val deg = Math.toDegrees(theta) % 90.0
                if (deg < NUMBER_GAP_DEG || deg > 90.0 - NUMBER_GAP_DEG) continue
                val c = cos(theta); val s = sin(theta)
                if (exclude(PointMm(r0 * c, r0 * s))) continue
                var ok = true
                for (i in 0 until m) {
                    val r = r0 - w + i * STEP_MM
                    val (x, y) = h.map(r * c, r * s)
                    val v = gray.sample(x, y)
                    if (v.isNaN()) { ok = false; break }
                    profile[i] = v
                }
                if (!ok) continue
                val found = locate(profile, m, kind) ?: continue
                val r = r0 - w + found * STEP_MM
                val (x, y) = h.map(r * c, r * s)
                out += RingSample(ring, theta, r, Point(x, y))
            }
        }
        return out
    }

    private enum class Kind { DARK_LINE, LIGHT_LINE, EDGE }

    /** Index (fractional) of the line in [p], or null if nothing line-like stands out. */
    private fun locate(p: DoubleArray, n: Int, kind: Kind): Double? {
        when (kind) {
            Kind.EDGE -> {
                // Black inside (small radius), paper outside: strongest rise over ~1 mm.
                val d = (5 / STEP_MM / 10).roundToInt().coerceAtLeast(2) * 2
                var best = -1; var bestV = 0.0
                for (i in d until n - d) {
                    val g = p[i + d] - p[i - d]
                    if (g > bestV) { bestV = g; best = i }
                }
                if (best < 0 || bestV < 40) return null
                return best.toDouble()
            }
            else -> {
                val sign = if (kind == Kind.DARK_LINE) -1.0 else 1.0
                var best = -1; var bestV = Double.NEGATIVE_INFINITY
                for (i in 1 until n - 1) {
                    val v = sign * p[i]
                    if (v > bestV) { bestV = v; best = i }
                }
                if (best <= 0 || best >= n - 1) return null
                // Must stand out from the surrounding paper: compare with the
                // profile away from the peak (more than 1 mm either side).
                val away = (1.0 / STEP_MM).roundToInt()
                val rest = (0 until n).filter { kotlin.math.abs(it - best) > away }.map { p[it] }.sorted()
                if (rest.size < 6) return null
                val median = rest[rest.size / 2]
                val mad = rest.map { kotlin.math.abs(it - median) }.sorted()[rest.size / 2].coerceAtLeast(1.0)
                val contrast = sign * (p[best] - median)
                if (contrast < 12 || contrast < 5 * mad) return null
                // Sub-sample refinement with a parabola through the peak.
                val a = p[best - 1]; val b = p[best]; val c = p[best + 1]
                val den = a - 2 * b + c
                val off = if (abs(den) > 1e-9) 0.5 * (a - c) / den else 0.0
                return best + off.coerceIn(-1.0, 1.0)
            }
        }
    }

    /** Debug image: every ring sample (green = agreed, red = ignored) and the fitted ideal rings. */
    fun drawDebug(bgr: Mat, r: RingFitResult): Mat {
        val out = bgr.clone()
        val t = max(1, (out.cols() / 1200.0).roundToInt())
        drawRings(out, r.map, YELLOW, t)
        r.samples.forEachIndexed { i, s -> Imgproc.circle(out, s.foundPx, t + 1, if (r.inlier[i]) GREEN else RED, -1) }
        return captioned(out, "Step 2: ring lines. Green = printed line found and used, red = ignored (holes, pen, paper edge)", "Yellow = ideal rings after fitting. Typical miss %.2f mm, 95%% within %.2f mm (%d points)".format(
            r.medianErrorMm, r.p95ErrorMm, r.inlierCount))
    }

    /** Draws the ideal rings through [h] as polylines (they are ellipses-ish under perspective). */
    fun drawRings(img: Mat, h: CardToPhoto, colour: org.opencv.core.Scalar, thickness: Int) {
        for (ring in CardGeometry.rings) {
            val r = CardGeometry.ringRadiusMm(ring)
            val pts = (0..360).map { k -> h.toPx(PointMm(r * cos(k * PI / 180), r * sin(k * PI / 180))) }
            val col = if (ring == CardGeometry.BLACK_OUTER_RING) MAGENTA else colour
            Imgproc.polylines(img, listOf(org.opencv.core.MatOfPoint(*pts.toTypedArray())), false, col, thickness, Imgproc.LINE_AA)
        }
        val c = h.toPx(PointMm.ORIGIN)
        Imgproc.drawMarker(img, c, RED, Imgproc.MARKER_CROSS, (8 * h.pxPerMmAt()).roundToInt(), thickness)
    }

}
