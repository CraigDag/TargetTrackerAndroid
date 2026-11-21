package rangetarget.core.session

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.PointMm
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One new hole found in a round, before it is given to a group. */
data class FoundHole(
    val centreMm: PointMm,
    val source: ShotSource,
    val confidence: Double,
    /** Which blob of new paper loss it came from (several holes can share one). */
    val blob: Int,
)

/**
 * Turns the blobs of newly missing paper into hole centres.
 *
 * A clean, round blob is one hole, centred on its middle. Anything else is
 * covered with circles the size of one hole, one at a time, each placed where
 * it explains the most missing paper: two touching holes become two circles,
 * and a crescent next to an old hole (a shot that went partly through it,
 * docs/SPEC.md Edge cases) becomes one circle that also overlaps the old hole.
 */
class HoleFitter(
    holes: Mat,
    oldHoles: Mat,
    ink: Mat,
    private val box: ViewBox,
    /** Diameter of a hole as it shows in the photo (a little bigger than the bullet: torn rim). */
    val visibleDiameterMm: Double,
) {
    private val w = holes.cols()
    private val h = holes.rows()
    private val ppm = box.pxPerMm
    private val r = visibleDiameterMm / 2 * ppm
    private val discArea = PI * r * r
    private val label = IntArray(w * h)
    /** Where a circle may extend without penalty: the blob itself, old holes and ink (which can cover a hole edge). */
    private val allowed: ByteArray
    private val nearOld: ByteArray
    private val offsets: IntArray // disc pixel offsets as (dy * BIG + dx) pairs
    private val blobs: List<Blob>

    class Blob(val id: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int, val area: Int, val cx: Double, val cy: Double,
               val solidity: Double, val touchesOld: Boolean) {
        var circles: List<Point> = emptyList()
        var uncovered: Int = 0
    }

    init {
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(holes, labels, stats, cents, 8, CvType.CV_32S)
        labels.get(0, 0, label)
        val st = IntArray(n * 5).also { stats.get(0, 0, it) }
        val ct = DoubleArray(n * 2).also { cents.get(0, 0, it) }
        labels.release(); stats.release(); cents.release()
        val old = ByteArray(w * h).also { oldHoles.get(0, 0, it) }
        val inkB = ByteArray(w * h).also { ink.get(0, 0, it) }
        // "Near an old hole": within 0.6 mm of one (the old edge may have frayed a little).
        val dil = Mat()
        Imgproc.dilate(oldHoles, dil, ChangeDetector.disc((1.2 * ppm).roundToInt() or 1))
        nearOld = ByteArray(w * h).also { dil.get(0, 0, it) }
        dil.release()
        allowed = ByteArray(w * h) { i -> if (label[i] > 0 || old[i].toInt() != 0 || inkB[i].toInt() != 0 || nearOld[i].toInt() != 0) 1 else 0 }
        val ri = r.toInt() + 1
        val offs = ArrayList<Int>()
        for (dy in -ri..ri) for (dx in -ri..ri) if (dx * dx + dy * dy <= r * r) { offs += dy; offs += dx }
        offsets = offs.toIntArray()
        blobs = (1 until n).mapNotNull { i ->
            val area = st[i * 5 + 4]
            val x0 = st[i * 5]; val y0 = st[i * 5 + 1]; val x1 = x0 + st[i * 5 + 2] - 1; val y1 = y0 + st[i * 5 + 3] - 1
            var touches = false
            for (y in y0..y1) for (x in x0..x1) { val k = y * w + x; if (label[k] == i && nearOld[k].toInt() != 0) { touches = true; break } }
            // Tiny specks are noise, unless they sit against an old hole (a shot
            // nearly straight through it leaves only a thin new crescent).
            val minArea = if (touches) 0.12 * discArea else 0.25 * discArea
            if (area < minArea) null
            else Blob(i, x0, y0, x1, y1, area, ct[i * 2], ct[i * 2 + 1], solidity(i, x0, y0, x1, y1, area), touches)
        }
    }

    /** Fits every blob and returns the holes. */
    fun fitAll(): List<FoundHole> {
        for (b in blobs) fit(b, null)
        return holes()
    }

    fun holes(): List<FoundHole> = blobs.flatMap { b ->
        val ratio = b.area / discArea
        b.circles.map { c ->
            val source = when {
                b.touchesOld -> ShotSource.GROWN_HOLE
                b.circles.size > 1 -> ShotSource.MERGED
                else -> ShotSource.CLEAN
            }
            val conf = when (source) {
                ShotSource.CLEAN -> if (ratio in 0.6..1.6 && b.solidity > 0.85) 0.95 else 0.7
                ShotSource.MERGED -> 0.7
                ShotSource.GROWN_HOLE -> 0.6
            } * (1.0 - 0.5 * (b.uncovered / discArea).coerceIn(0.0, 1.0))
            FoundHole(box.toMm(c.x + 0.5, c.y + 0.5), source, conf, b.id)
        }
    }

    /**
     * Blobs with how much more missing paper they have than their holes explain
     * (in holes), most first. Used when fewer holes were found than shots fired.
     */
    fun splitCandidates(): List<Pair<Int, Double>> = blobs
        // A crescent next to an old hole can't be split reliably: where the second
        // shot went inside the old hole leaves no trace (validation, Phase 4).
        .filter { !it.touchesOld }
        .map { it.id to (it.area / discArea - it.circles.size + it.uncovered / discArea) }
        .sortedByDescending { it.second }

    /**
     * Circles in blobs with more than one, with how much missing paper only that
     * circle explains (in holes), least first. Used when more holes were found
     * than shots fired.
     */
    fun mergeCandidates(): List<Pair<Int, Double>> = blobs.filter { it.circles.size > 1 }.map { b ->
        val alone = b.circles.indices.minOf { k ->
            val others = HashSet<Int>(); b.circles.forEachIndexed { j, c -> if (j != k) cover(b, c, others) }
            val mine = HashSet<Int>(); cover(b, b.circles[k], mine)
            (mine.count { it !in others }) / discArea
        }
        b.id to alone
    }.sortedBy { it.second }

    /** Middle of a blob, in card mm. */
    fun blobCentre(blobId: Int): PointMm? = blobs.firstOrNull { it.id == blobId }?.let { box.toMm(it.cx + 0.5, it.cy + 0.5) }

    /** Re-fits a blob with one hole fewer than before. */
    fun removeHole(blobId: Int): Boolean {
        val b = blobs.firstOrNull { it.id == blobId } ?: return false
        if (b.circles.size < 2) return false
        fit(b, b.circles.size - 1)
        return true
    }

    /** Re-fits a blob with one more hole than before (for a "split this hole" tap on the confirm screen). */
    fun addHole(blobId: Int): Boolean {
        val b = blobs.firstOrNull { it.id == blobId } ?: return false
        fit(b, b.circles.size + 1)
        return true
    }

    private fun fit(b: Blob, forced: Int?) {
        val ratio = b.area / discArea
        // One round hole, or a blob that must be one hole (a group had too many): its middle.
        if ((forced == null && !b.touchesOld && b.solidity > 0.85 && ratio in 0.55..1.55) || (forced == 1 && !b.touchesOld)) {
            b.circles = listOf(Point(b.cx, b.cy)); b.uncovered = 0; return
        }
        val covered = HashSet<Int>()
        val circles = ArrayList<Point>()
        val maxK = forced ?: max(1, (ratio * 1.6).roundToInt() + 1)
        // Two shots closer than about half a hole can't be told apart; don't invent them.
        val minSep = (if (forced != null) 0.3 else 0.5) * 2 * r
        while (circles.size < maxK) {
            val best = bestCircle(b, covered, null, circles, minSep)
            if (best.second == Double.NEGATIVE_INFINITY) break
            val gain = best.second
            if (forced == null && circles.isNotEmpty()) {
                val left = b.area - covered.size
                if (left < MIN_EXTRA_LEFT * discArea || gain < MIN_EXTRA_GAIN * discArea) break
            }
            circles += best.first
            cover(b, best.first, covered)
        }
        // Polish: move each circle to its best spot with the others held still.
        repeat(2) {
            for (k in circles.indices) {
                val others = HashSet<Int>()
                circles.forEachIndexed { j, c -> if (j != k) cover(b, c, others) }
                circles[k] = bestCircle(b, others, circles[k], circles.filterIndexed { j, _ -> j != k }, minSep).first
            }
        }
        val all = HashSet<Int>(); circles.forEach { cover(b, it, all) }
        b.circles = circles
        b.uncovered = b.area - all.size
    }

    /** Best circle centre: most uncovered blob pixels inside, minus pixels that must be paper. */
    private fun bestCircle(b: Blob, covered: Set<Int>, near: Point?, others: List<Point> = emptyList(), minSep: Double = 0.0): Pair<Point, Double> {
        val ri = r.roundToInt()
        val (xs, ys) = if (near == null) Pair((b.x0 - ri / 2)..(b.x1 + ri / 2), (b.y0 - ri / 2)..(b.y1 + ri / 2))
        else Pair((near.x.roundToInt() - 4)..(near.x.roundToInt() + 4), (near.y.roundToInt() - 4)..(near.y.roundToInt() + 4))
        val step = if (near == null) 2 else 1
        var best = Point(b.cx, b.cy); var bestScore = Double.NEGATIVE_INFINITY
        for (cy in ys step step) for (cx in xs step step) {
            if (others.any { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) < minSep * minSep }) continue
            val s = score(b, cx, cy, covered)
            if (s > bestScore) { bestScore = s; best = Point(cx.toDouble(), cy.toDouble()) }
        }
        if (near == null) { // refine the coarse grid
            val c = bestCircle(b, covered, best, others, minSep); return c
        }
        return best to bestScore
    }

    private fun score(b: Blob, cx: Int, cy: Int, covered: Set<Int>): Double {
        var s = 0.0
        var i = 0
        while (i < offsets.size) {
            val y = cy + offsets[i]; val x = cx + offsets[i + 1]; i += 2
            if (x < 0 || y < 0 || x >= w || y >= h) { s -= 1.0; continue }
            val k = y * w + x
            if (label[k] == b.id) { if (k !in covered) s += 1.0 }
            else if (allowed[k].toInt() == 0) s -= 1.0
        }
        return s
    }

    private fun cover(b: Blob, c: Point, into: MutableSet<Int>) {
        val cx = c.x.roundToInt(); val cy = c.y.roundToInt()
        var i = 0
        while (i < offsets.size) {
            val y = cy + offsets[i]; val x = cx + offsets[i + 1]; i += 2
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            val k = y * w + x
            if (label[k] == b.id) into += k
        }
    }

    private fun solidity(id: Int, x0: Int, y0: Int, x1: Int, y1: Int, area: Int): Double {
        val pts = ArrayList<Point>()
        for (y in y0..y1) for (x in x0..x1) if (label[y * w + x] == id) {
            val edge = x == x0 || y == y0 || x == x1 || y == y1 || label[y * w + x - 1] != id || label[y * w + x + 1] != id ||
                label[(y - 1) * w + x] != id || label[(y + 1) * w + x] != id
            if (edge) pts += Point(x.toDouble(), y.toDouble())
        }
        if (pts.size < 5) return 1.0
        val hullIdx = MatOfInt()
        Imgproc.convexHull(MatOfPoint(*pts.toTypedArray()), hullIdx)
        val hull = MatOfPoint(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        val hullArea = Imgproc.contourArea(hull) + 0.5 * min(pts.size, 2 * (x1 - x0 + y1 - y0 + 2))
        return (area / hullArea).coerceAtMost(1.0)
    }

    companion object {
        /** Another circle goes into a merged blob only if this much of the blob (in holes) is still unexplained... */
        const val MIN_EXTRA_LEFT = 0.5
        /** ...and the circle explains at least this much of it (in holes). */
        const val MIN_EXTRA_GAIN = 0.5

        /**
         * Visible hole size from this round's clean, separate holes (median of
         * round blobs not touching old holes), or null if there are too few.
         */
        fun measureVisibleDiameterMm(holes: Mat, oldHoles: Mat, box: ViewBox): Double? {
            val ppm = box.pxPerMm
            val probe = HoleFitter(holes, oldHoles, Mat.zeros(holes.size(), CvType.CV_8UC1), box, 5.0)
            val d = probe.blobs.filter { !it.touchesOld && it.solidity > 0.88 }
                .map { 2 * sqrt(it.area / PI) / ppm }.filter { it in 4.0..16.0 }.sorted()
            if (d.size < 3) return null
            // Lower half leans on single holes rather than pairs that look round.
            return d[(d.size * 0.4).toInt()]
        }
    }
}
