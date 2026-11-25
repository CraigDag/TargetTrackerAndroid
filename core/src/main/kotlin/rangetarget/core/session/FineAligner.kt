package rangetarget.core.session

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.card.CardMap
import rangetarget.core.card.CardToPhoto
import rangetarget.core.card.TopDown
import rangetarget.core.geometry.PointMm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A card map with a small, smooth correction on top: the card point y is shown
 * by what [base] shows at y − shift(y). The shift lines one photo up with the
 * previous one to a fraction of a millimetre (docs/SPEC.md, Image processing
 * step 3), soaking up what the ring fit alone can't see (paper curl that
 * changed between photos, and turn, which round rings can't tell).
 */
class AlignedMap(val base: CardToPhoto, val field: ShiftField) : CardToPhoto {
    override fun map(x: Double, y: Double): Pair<Double, Double> {
        val (sx, sy) = field.at(x, y)
        return base.map(x - sx, y - sy)
    }

    override fun toMm(px: Point): PointMm {
        val p = base.toMm(px)
        // y − shift(y) = p; the shift is small and smooth, so a few fixed-point steps do.
        var y = p
        repeat(6) { val (sx, sy) = field.at(y.x, y.y); y = PointMm(p.x + sx, p.y + sy) }
        return y
    }
}

/** A smooth 2-D shift in mm over the card, as a cubic in card position (same terms as [CardMap]'s bend). */
class ShiftField(val cx: DoubleArray, val cy: DoubleArray) {
    fun at(x: Double, y: Double): Pair<Double, Double> {
        val b = CardMap.basis(x, y)
        var sx = 0.0; var sy = 0.0
        for (k in b.indices) { sx += cx[k] * b[k]; sy += cy[k] * b[k] }
        return Pair(sx, sy)
    }

    operator fun plus(o: ShiftField) = ShiftField(DoubleArray(cx.size) { cx[it] + o.cx[it] }, DoubleArray(cy.size) { cy[it] + o.cy[it] })

    /** Largest shift anywhere on the card, mm. */
    fun largestMm(): Double {
        var worst = 0.0
        var x = -260.0
        while (x <= 260.0) { var y = -250.0; while (y <= 250.0) { val (a, b) = at(x, y); worst = max(worst, hypot(a, b)); y += 20.0 }; x += 20.0 }
        return worst
    }

    companion object {
        val ZERO get() = ShiftField(DoubleArray(CardMap.TERMS), DoubleArray(CardMap.TERMS))
    }
}

/** One patch compared between the two photos. */
data class PatchMatch(
    val centreMm: PointMm,
    /** Where this photo's patch content sits in the previous photo, relative to here, mm. */
    val shiftMm: PointMm,
    /** 2×2 confidence (how sharply the match peaks in each direction), row-major. */
    val weight: DoubleArray,
    val score: Double,
    var used: Boolean = true,
)

/**
 * Lines a photo up with the previous one by comparing small patches of the
 * card (ring lines, numbers, aim points, earlier holes and loops). A patch
 * holding only one ring line can say how far the line moved across itself
 * but not along itself, so each patch carries how sharply its match peaks in
 * each direction, and the smooth correction is fitted with those weights.
 */
object FineAligner {
    private const val PATCH_STEP_MM = 20.0
    private const val PATCH_HALF_MM = 16.0
    private const val MIN_SCORE = 0.55
    private const val EXTRA_WEIGHT = 8.0

    class Result(val map: AlignedMap, val view: TopDown, val summary: AlignmentSummary, val patches: List<PatchMatch>)

    /**
     * Aligns [photo] (card map [start]) to the previous aligned view [prevGray]
     * (8-bit grey, same layout as [viewBox]); patches are only taken where
     * [usable] is non-zero.
     */
    fun align(photo: Mat, start: CardToPhoto, prevGray: Mat, usable: Mat, viewBox: ViewBox, extraMm: List<PointMm> = emptyList()): Result {
        var field = ShiftField.ZERO
        var patches: List<PatchMatch> = emptyList()
        var tried = 0
        for ((pass, searchMm) in listOf(6.0, 2.0, 1.0).withIndex()) {
            val view = viewBox.warp(photo, AlignedMap(start, field), Imgproc.INTER_LINEAR)
            val gray = grayBlur(view.image)
            view.image.release()
            patches = measure(gray, prevGray, usable, viewBox, searchMm, extraMm)
            gray.release()
            tried = patches.size
            if (pass == 2) break // last pass only measures what is left over
            val step = fit(patches)
            field += step
        }
        val final = AlignedMap(start, field)
        val view = viewBox.warp(photo, final, Imgproc.INTER_LINEAR)
        val leftovers = patches.filter { it.used }.map { leftoverAlongStrongest(it) }.sorted()
        val summary = AlignmentSummary(
            patchesUsed = leftovers.size, patchesTried = tried,
            largestCorrectionMm = field.largestMm(),
            leftoverMedianMm = leftovers.getOrElse(leftovers.size / 2) { Double.NaN }.finiteOr(9.9),
            leftoverP95Mm = leftovers.getOrElse((leftovers.size * 0.95).toInt().coerceAtMost(leftovers.size - 1)) { Double.NaN }.finiteOr(9.9),
        )
        return Result(final, view, summary, patches)
    }

    fun grayBlur(bgr: Mat): Mat {
        val g = Mat()
        Imgproc.cvtColor(bgr, g, Imgproc.COLOR_BGR2GRAY)
        Imgproc.GaussianBlur(g, g, Size(0.0, 0.0), 1.0)
        return g
    }

    /**
     * Patch centres: a regular grid, plus [extraMm] (places with detail in both
     * directions, such as aim points and the printed ring numbers, which pin down
     * the slide along the rings that ring lines alone can't).
     */
    private fun centres(box: ViewBox, extraMm: List<PointMm>): List<PointMm> = buildList {
        var y = box.bottom + PATCH_STEP_MM / 2
        while (y < box.top) {
            var x = box.left + PATCH_STEP_MM / 2
            while (x < box.right) { add(PointMm(x, y)); x += PATCH_STEP_MM }
            y += PATCH_STEP_MM
        }
        addAll(extraMm)
    }

    private fun measure(cur: Mat, prev: Mat, usable: Mat, box: ViewBox, searchMm: Double, extraMm: List<PointMm>): List<PatchMatch> {
        val ppm = box.pxPerMm
        val half = (PATCH_HALF_MM * ppm).roundToInt()
        val s = max(2, (searchMm * ppm).roundToInt())
        val out = ArrayList<PatchMatch>()
        val res = Mat()
        val all = centres(box, extraMm)
        val firstExtra = all.size - extraMm.size
        for ((idx, centre) in all.withIndex()) {
            val x = centre.x; val y = centre.y
            // Detail spots are few among hundreds of ring patches, but they alone see slides along the rings.
            val boost = if (idx >= firstExtra) EXTRA_WEIGHT else 1.0
            val c = box.toPx(centre)
            val cx = c.x.roundToInt(); val cy = c.y.roundToInt()
            if (cx - half - s < 0 || cy - half - s < 0 || cx + half + s >= cur.cols() || cy + half + s >= cur.rows()) continue
            if (usable.get(cy, cx)[0] == 0.0) continue
            val tpl = cur.submat(Rect(cx - half, cy - half, 2 * half + 1, 2 * half + 1))
            val mean = org.opencv.core.MatOfDouble(); val sd = org.opencv.core.MatOfDouble()
            Core.meanStdDev(tpl, mean, sd)
            if (sd.toArray()[0] < 6.0) continue // plain paper: nothing to match on
            val search = prev.submat(Rect(cx - half - s, cy - half - s, 2 * (half + s) + 1, 2 * (half + s) + 1))
            Imgproc.matchTemplate(search, tpl, res, Imgproc.TM_CCOEFF_NORMED)
            val mm = Core.minMaxLoc(res)
            val px = mm.maxLoc.x.toInt(); val py = mm.maxLoc.y.toInt()
            if (mm.maxVal < MIN_SCORE || px == 0 || py == 0 || px == res.cols() - 1 || py == res.rows() - 1) continue
            val r = Array(3) { j -> DoubleArray(3) { i -> res.get(py + j - 1, px + i - 1)[0] } }
            // Quadratic through the 3×3 around the peak: gradient and curvature.
            val gx = (r[1][2] - r[1][0]) / 2; val gy = (r[2][1] - r[0][1]) / 2
            val hxx = r[1][2] - 2 * r[1][1] + r[1][0]; val hyy = r[2][1] - 2 * r[1][1] + r[0][1]
            val hxy = (r[2][2] - r[2][0] - r[0][2] + r[0][0]) / 4
            // Curvature matrix (positive = sharp peak), clipped to be non-negative.
            val w = psd(-hxx, -hxy, -hyy)
            val reg = 1e-4
            val a = w[0] + reg; val b = w[1]; val d = w[3] + reg
            val det = a * d - b * b
            // Peak offset = W⁻¹ g (sub-pixel), only meaningful in the sharp direction(s).
            val ox = ((d * gx - b * gy) / det).coerceIn(-1.0, 1.0)
            val oy = ((-b * gx + a * gy) / det).coerceIn(-1.0, 1.0)
            val dxPx = px - s + ox; val dyPx = py - s + oy
            // Pixel shift → mm (image y runs down, card y runs up); weight per mm².
            val k = ppm * ppm * boost
            out += PatchMatch(PointMm(x, y), PointMm(dxPx / ppm, -dyPx / ppm),
                doubleArrayOf(w[0] * k, -w[1] * k, -w[2] * k, w[3] * k), mm.maxVal)
        }
        res.release()
        return out
    }

    /** Nearest positive semi-definite 2×2 to [[a,b],[b,d]]. */
    private fun psd(a: Double, b: Double, d: Double): DoubleArray {
        val tr = a + d; val det = a * d - b * b
        val disc = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + disc; val l2 = tr / 2 - disc
        val (v1x, v1y) = if (abs(b) > 1e-12) Pair(l1 - d, b) else if (a >= d) Pair(1.0, 0.0) else Pair(0.0, 1.0)
        val n = hypot(v1x, v1y); val ux = v1x / n; val uy = v1y / n
        val e1 = max(0.0, l1); val e2 = max(0.0, l2)
        // e1 u uᵀ + e2 v vᵀ, with v ⟂ u.
        return doubleArrayOf(e1 * ux * ux + e2 * uy * uy, (e1 - e2) * ux * uy, (e1 - e2) * ux * uy, e1 * uy * uy + e2 * ux * ux)
    }

    /**
     * Weighted least squares for the smooth correction, dropping patches that
     * disagree (a new hole or loop changes a patch and gives a false match).
     */
    private fun fit(patches: List<PatchMatch>): ShiftField {
        val n = CardMap.TERMS
        var field = ShiftField.ZERO
        repeat(3) { iter ->
            val ata = Array(2 * n) { DoubleArray(2 * n) }
            val atb = DoubleArray(2 * n)
            var used = 0
            for (p in patches) {
                if (iter > 0) {
                    val (fx, fy) = field.at(p.centreMm.x, p.centreMm.y)
                    val ex = p.shiftMm.x - fx; val ey = p.shiftMm.y - fy
                    p.used = along(p, ex, ey) < max(0.35, 1.2 - 0.4 * iter)
                }
                if (!p.used) continue
                used++
                val b = CardMap.basis(p.centreMm.x, p.centreMm.y)
                val w = p.weight
                for (i in 0 until n) for (j in 0 until n) {
                    val bb = b[i] * b[j]
                    ata[i][j] += w[0] * bb; ata[i][n + j] += w[1] * bb
                    ata[n + i][j] += w[2] * bb; ata[n + i][n + j] += w[3] * bb
                }
                for (i in 0 until n) {
                    atb[i] += b[i] * (w[0] * p.shiftMm.x + w[1] * p.shiftMm.y)
                    atb[n + i] += b[i] * (w[2] * p.shiftMm.x + w[3] * p.shiftMm.y)
                }
            }
            if (used < 12) return field
            // Keep higher terms small unless the patches really ask for them.
            val scale = (0 until 2 * n).maxOf { ata[it][it] }.coerceAtLeast(1e-9)
            for (i in 0 until 2 * n) ata[i][i] += scale * (if (i % n < 3) 1e-6 else 1e-3)
            val c = solve(ata, atb)
            field = ShiftField(c.copyOfRange(0, n), c.copyOfRange(n, 2 * n))
        }
        return field
    }

    /** Size of an error (ex, ey) along the patch's sharpest direction, mm. */
    private fun along(p: PatchMatch, ex: Double, ey: Double): Double {
        val w = p.weight
        val tr = w[0] + w[3]
        if (tr <= 0) return 0.0
        val q = ex * (w[0] * ex + w[1] * ey) + ey * (w[2] * ex + w[3] * ey)
        val lmax = tr / 2 + sqrt(max(0.0, tr * tr / 4 - (w[0] * w[3] - w[1] * w[2])))
        return sqrt(max(0.0, q / lmax))
    }

    private fun leftoverAlongStrongest(p: PatchMatch) = along(p, p.shiftMm.x, p.shiftMm.y)

    private fun Double.finiteOr(v: Double) = if (isFinite()) this else v

    internal fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray {
        val n = bIn.size
        val a = Array(n) { aIn[it].copyOf() }; val b = bIn.copyOf()
        for (col in 0 until n) {
            val piv = (col until n).maxBy { abs(a[it][col]) }
            a[col] = a[piv].also { a[piv] = a[col] }; b[col] = b[piv].also { b[piv] = b[col] }
            if (abs(a[col][col]) < 1e-15) continue
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                if (f == 0.0) continue
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            if (abs(a[r][r]) < 1e-15) continue
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }
}

/** The flattened view every round is drawn into: card mm box and resolution. */
data class ViewBox(val pxPerMm: Double, val left: Double, val right: Double, val bottom: Double, val top: Double) {
    val widthPx get() = ((right - left) * pxPerMm).roundToInt()
    val heightPx get() = ((top - bottom) * pxPerMm).roundToInt()
    fun toPx(p: PointMm) = Point((p.x - left) * pxPerMm, (top - p.y) * pxPerMm)
    fun toMm(x: Double, y: Double) = PointMm(left + x / pxPerMm, top - y / pxPerMm)
    fun warp(photo: Mat, map: CardToPhoto, interpolation: Int = Imgproc.INTER_LINEAR): TopDown =
        TopDown.warp(photo, map, pxPerMm, left, right, bottom, top, interpolation)

    companion object {
        /** Paper plus a small margin, at 4 px/mm (a 9 mm hole is ~36 px across). */
        val DEFAULT = ViewBox(4.0, -290.0, 290.0, -275.0, 275.0)
    }
}
