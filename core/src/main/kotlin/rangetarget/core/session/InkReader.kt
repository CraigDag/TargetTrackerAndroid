package rangetarget.core.session

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.PointMm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Names for marker colours, for display only. Which colours count as "the
 * same" in a round is decided by clustering the ink actually seen, not by
 * these names. Reference values are typical paint-marker colours (sRGB).
 */
object MarkerColours {
    val NAMED: List<Pair<String, String>> = listOf(
        "yellow" to "#FFE014", "orange" to "#FF8A12", "lime" to "#8FDC28", "green" to "#2EA84A",
        "light_blue" to "#3FB2FF", "dark_blue" to "#2A3F9F", "pink" to "#FF3D9E", "red" to "#D7262B",
        "purple" to "#7B3FA0", "white" to "#F2F2F2",
    )

    /** Nearest named colour by hue and colourfulness (Lab a/b plane), ignoring how light it is. */
    fun nameOf(rgb: String): String {
        val (a, b) = labAb(rgb)
        return NAMED.minBy { (_, ref) -> val (ra, rb) = labAb(ref); hypot(a - ra, b - rb) }.first
    }

    fun hex(r: Double, g: Double, b: Double) = "#%02X%02X%02X".format(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))

    /** BGR scalar for drawing. */
    fun bgr(rgb: String): Scalar {
        val h = rgb.removePrefix("#")
        return Scalar(h.substring(4, 6).toInt(16).toDouble(), h.substring(2, 4).toInt(16).toDouble(), h.substring(0, 2).toInt(16).toDouble())
    }

    /** CIE a*, b* of an sRGB colour (D65). */
    fun labAb(rgb: String): Pair<Double, Double> {
        val s = bgr(rgb)
        fun lin(c: Double): Double { val v = c / 255; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        val r = lin(s.`val`[2]); val g = lin(s.`val`[1]); val b = lin(s.`val`[0])
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
        return Pair(500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }
}

/**
 * Sorts this round's new ink by colour and decides which marks are loops
 * (docs/SPEC.md, Image processing step 4 and Edge cases: stray ink, colour
 * used twice). Colours are found from the ink itself: hues that occur a lot
 * become colour groups, so a marker brand or shade the app hasn't seen still
 * works.
 */
object InkReader {
    /** A loop must enclose at least this much paper, mm². */
    private const val MIN_ENCLOSED_MM2 = 150.0
    /** Ink marks smaller than this are ignored (specks), mm². */
    private const val MIN_INK_MM2 = 25.0
    /** Gaps up to about this wide are bridged when deciding if a loop is closed, mm. */
    private val GAP_STEPS_MM = doubleArrayOf(2.5, 5.0)
    /** Holes and old ink within this distance of a stroke can bridge it, mm. */
    private const val NEAR_MM = 12.0
    /**
     * Off the paper within this distance of a stroke closes a loop that runs off
     * the edge, mm. Long enough to span the stretch of edge a group's loop is cut
     * by, short enough that the band never runs round the whole card.
     */
    private const val EDGE_REACH_MM = 45.0

    class Read(val marks: List<InkMark>, /** Per mark: its filled region (loop interior + stroke). */ val regions: Map<Int, Mat>) {
        fun release() = regions.values.forEach { it.release() }
    }

    fun read(ink: Mat, cur: LabView, curBgr: Mat, box: ViewBox, aimDiscs: List<Pair<PointMm, Double>>, holes: Mat, offCard: Mat, prev: LabView): Read {
        val w = cur.width; val h = cur.height
        val mask = ByteArray(w * h).also { ink.get(0, 0, it) }
        val idx = mask.indices.filter { mask[it].toInt() != 0 }
        val ppm = box.pxPerMm
        if (idx.isEmpty()) return Read(emptyList(), emptyMap())

        // Hue histogram of the ink (angle in the Lab a/b plane), 5° bins.
        val hue = FloatArray(idx.size) { k -> val i = idx[k]; ((Math.toDegrees(atan2(cur.b(i).toDouble(), cur.a(i).toDouble())) + 360) % 360).toFloat() }
        val hist = DoubleArray(72)
        for (v in hue) hist[(v / 5).toInt() % 72] += 1.0
        val sm = DoubleArray(72) { b -> (-2..2).sumOf { d -> hist[(b + d + 72) % 72] * (3 - abs(d)) } / 9.0 }
        val minPx = MIN_INK_MM2 * ppm * ppm
        val peaks = (0 until 72).filter { b -> sm[b] >= sm[(b + 71) % 72] && sm[b] > sm[(b + 1) % 72] && (-3..3).sumOf { hist[(b + it + 72) % 72] } >= minPx }
            .map { it * 5.0 + 2.5 }
        // Peaks closer than 25° are the same colour.
        val merged = ArrayList<Double>()
        for (p in peaks.sortedByDescending { sm[(it / 5).toInt()] }) if (merged.none { angDiff(it, p) < 25 }) merged += p
        val colourLabel = IntArray(w * h) { -1 }
        for ((k, i) in idx.withIndex()) {
            val best = merged.indices.minByOrNull { angDiff(merged[it], hue[k].toDouble()) } ?: continue
            if (angDiff(merged[best], hue[k].toDouble()) <= 35) colourLabel[i] = best
        }
        val bgr = ByteArray(w * h * 3).also { curBgr.get(0, 0, it) }
        val discMask = Mat.zeros(h, w, CvType.CV_8UC1)
        for ((c0, d) in aimDiscs) Imgproc.circle(discMask, box.toPx(c0), ((d / 2 + 1.0) * ppm).roundToInt(), Scalar(255.0), -1)
        val marks = ArrayList<InkMark>()
        val regions = HashMap<Int, Mat>()
        for (c in merged.indices) {
            val cm = Mat(h, w, CvType.CV_8UC1)
            cm.put(0, 0, ByteArray(w * h) { if (colourLabel[it] == c) -1 else 0 })
            Imgproc.morphologyEx(cm, cm, Imgproc.MORPH_CLOSE, ChangeDetector.disc((1.5 * ppm).roundToInt() or 1))
            val colourPx = ByteArray(w * h).also { cm.get(0, 0, it) }
            // Ink of this colour already on the card: a new loop crossing an old one of
            // the same colour shows no change there, so it can bridge the stroke.
            val oldSame = Mat(h, w, CvType.CV_8UC1).also { m -> m.put(0, 0, ByteArray(w * h) { i ->
                if (ChangeDetector.isInk(prev, i) && angDiff(Math.toDegrees(atan2(prev.b(i).toDouble(), prev.a(i).toDouble())).let { (it + 360) % 360 }, merged[c]) <= 30) -1 else 0 }) }
            fun meanRgb(region: Mat?): String {
                val inR = region?.let { r -> ByteArray(w * h).also { r.get(0, 0, it) } }
                var sb = 0.0; var sg = 0.0; var sr = 0.0; var cnt = 0
                for (i in 0 until w * h) if (colourLabel[i] == c && (inR == null || inR[i].toInt() != 0)) {
                    sb += bgr[3 * i].toInt() and 0xFF; sg += bgr[3 * i + 1].toInt() and 0xFF; sr += bgr[3 * i + 2].toInt() and 0xFF; cnt++
                }
                return if (cnt > 0) MarkerColours.hex(sr / cnt, sg / cnt, sb / cnt) else "#808080"
            }
            // What can stand for the loop's line when judging whether its convex hull is the
            // loop: this colour's new ink, old ink of the same colour next to it, the paper edge.
            val stroke = cm.clone()
            val support = Mat().also { sp ->
                Imgproc.dilate(cm, sp, ChangeDetector.disc((NEAR_MM * ppm).roundToInt() or 1))
                Core.bitwise_and(sp, oldSame, sp); Core.bitwise_or(sp, cm, sp); Core.bitwise_or(sp, offCard, sp)
                Imgproc.dilate(sp, sp, ChangeDetector.disc((3 * ppm).roundToInt() or 1))
            }
            fun add(kind: InkKind, region0: Mat, inkPx: Int) {
                val region = if (kind == InkKind.STRAY) region0 else fillToHull(region0, stroke, support, ppm)
                val id = marks.size + 1
                val outline = outlineOf(region, box)
                val m = Imgproc.moments(region, true)
                val centre = if (m.m00 > 0) box.toMm(m.m10 / m.m00, m.m01 / m.m00) else outline.firstOrNull() ?: PointMm.ORIGIN
                val rgb = meanRgb(region)
                marks += InkMark(id, MarkerColours.nameOf(rgb), rgb, kind, outline, centre, inkPx / (ppm * ppm))
                if (kind == InkKind.STRAY) region.release() else regions[id] = region
            }
            // Loops: as drawn (counting aim discs a stroke runs across), then with small gaps bridged.
            var remaining = cm
            // A loop drawn right at the paper edge can run off it: as a last resort the
            // edge closes it (flagged, like a gap).
            class Pass(val kind: InkKind, val gapMm: Double, val discs: Boolean, val edge: Boolean)
            val passes = listOf(Pass(InkKind.LOOP, 0.0, false, false), Pass(InkKind.LOOP, 0.0, true, false),
                Pass(InkKind.LOOP_WITH_GAP, GAP_STEPS_MM[0], true, false), Pass(InkKind.LOOP_WITH_GAP, GAP_STEPS_MM[1], true, false),
                Pass(InkKind.LOOP_AT_EDGE, GAP_STEPS_MM[0], true, true))
            for (pass in passes) {
                val kind = pass.kind; val gapMm = pass.gapMm
                val prepared = withTouchedDiscs(remaining, box, if (pass.discs) aimDiscs else emptyList(), holes,
                    listOfNotNull(if (pass.discs) oldSame to NEAR_MM else null, if (pass.edge) offCard to EDGE_REACH_MM else null))
                if (gapMm > 0) Imgproc.morphologyEx(prepared, prepared, Imgproc.MORPH_CLOSE, ChangeDetector.disc((gapMm * ppm).roundToInt() or 1))
                for ((outer, region) in enclosedRegions(prepared, ppm, discMask, remaining)) {
                    val inR = ByteArray(w * h).also { outer.get(0, 0, it) }
                    val inkPx = inR.indices.count { inR[it].toInt() != 0 && colourPx[it].toInt() != 0 }
                    if (inkPx < minPx || (pass.edge && mostlyEdge(outer, offCard, ppm))) { outer.release(); region.release(); continue }
                    val notOuter = Mat(); Core.bitwise_not(outer, notOuter)
                    Core.bitwise_and(remaining, notOuter, remaining); notOuter.release(); outer.release()
                    add(kind, region, inkPx)
                }
                prepared.release()
            }
            // A loop left open wider than the gap passes bridge, or broken into arcs where it
            // runs along an old loop of the same colour: nearby pieces whose ink follows most of
            // the outline of their joint convex hull are a loop with a gap.
            // Red is kept for printed aim-point stickers (docs/SPEC.md, loop palette): what's
            // left of a shot-out sticker must not be taken for an open loop.
            val isStickerRed = MarkerColours.nameOf(meanRgb(null)) == "red"
            for (region in if (isStickerRed) emptyList() else openLoops(remaining, oldSame, ppm)) {
                val inR = ByteArray(w * h).also { region.get(0, 0, it) }
                val inkPx = inR.indices.count { inR[it].toInt() != 0 && colourPx[it].toInt() != 0 }
                val notR = Mat(); Core.bitwise_not(region, notR)
                Core.bitwise_and(remaining, notR, remaining); notR.release()
                add(InkKind.LOOP_WITH_GAP, region, inkPx)
            }
            // What's left is stray ink, except bits inside an aim point disc: there a
            // loop crossing the coloured disc mixes with it and changes hue.
            val labels = Mat(); val stats = Mat(); val cents = Mat()
            val n = Imgproc.connectedComponentsWithStats(remaining, labels, stats, cents, 8, CvType.CV_32S)
            val lab = IntArray(w * h).also { labels.get(0, 0, it) }
            val st = IntArray(n * 5).also { stats.get(0, 0, it) }
            val ct = DoubleArray(n * 2).also { cents.get(0, 0, it) }
            for (comp in 1 until n) {
                if (st[comp * 5 + 4] < minPx) continue
                val centre = box.toMm(ct[comp * 2], ct[comp * 2 + 1])
                if (st[comp * 5 + 4] < 0.5 * PI * 15.0 * 15.0 * ppm * ppm && aimDiscs.any { (a, d) -> (centre - a).radius < d / 2 + 4.0 }) continue
                val region = Mat(h, w, CvType.CV_8UC1).also { m -> m.put(0, 0, ByteArray(w * h) { if (lab[it] == comp) -1 else 0 }) }
                add(InkKind.STRAY, region, st[comp * 5 + 4])
            }
            stroke.release(); support.release()
            cm.release(); oldSame.release(); labels.release(); stats.release(); cents.release()
        }
        discMask.release()
        return mergeOverlapping(marks, regions)
    }

    /**
     * [m] plus what a stroke can't show on: every aim disc it touches (a loop
     * vanishes where it crosses a disc of a similar colour) and holes next to
     * it (ink doesn't stay on a hole). A new caller-owned Mat.
     */
    private fun withTouchedDiscs(m: Mat, box: ViewBox, aimDiscs: List<Pair<PointMm, Double>>, holes: Mat, more: List<Pair<Mat, Double>>): Mat {
        val ppm = box.pxPerMm
        val out = m.clone()
        for ((gaps, reachMm) in listOf(holes to NEAR_MM) + more) {
            val near = Mat(); Imgproc.dilate(m, near, ChangeDetector.disc((reachMm * ppm).roundToInt() or 1))
            val g = Mat(); Imgproc.dilate(gaps, g, ChangeDetector.disc((1.5 * ppm).roundToInt() or 1))
            Core.bitwise_and(g, near, g)
            Core.bitwise_or(out, g, out)
            g.release(); near.release()
        }
        for ((c, d) in aimDiscs) {
            val p = box.toPx(c); val r = (d / 2 + 1.0) * ppm
            val disc = Mat.zeros(m.size(), CvType.CV_8UC1)
            Imgproc.circle(disc, p, (r + 3 * ppm).roundToInt(), Scalar(255.0), -1)
            Core.bitwise_and(disc, m, disc)
            if (Core.countNonZero(disc) > 0) Imgproc.circle(out, p, r.roundToInt(), Scalar(255.0), -1)
            disc.release()
        }
        return out
    }

    /**
     * Each part of [m] that encloses a big enough hole: its filled outer region,
     * and the region used for assigning holes, which leaves out aim discs that
     * only helped bridge the stroke (a hole in a disc the loop runs across isn't
     * inside the loop). Both are new caller-owned Mats.
     */
    private fun enclosedRegions(m: Mat, ppm: Double, discMask: Mat, stroke: Mat): List<Pair<Mat, Mat>> {
        val contours = ArrayList<MatOfPoint>(); val hier = Mat()
        Imgproc.findContours(m.clone(), contours, hier, Imgproc.RETR_CCOMP, Imgproc.CHAIN_APPROX_SIMPLE)
        if (contours.isEmpty()) return emptyList()
        val hv = IntArray(contours.size * 4).also { hier.get(0, 0, it) }
        hier.release()
        val minHole = MIN_ENCLOSED_MM2 * ppm * ppm
        val out = ArrayList<Pair<Mat, Mat>>()
        for (i in contours.indices) {
            if (hv[i * 4 + 3] >= 0) continue // a hole, not an outer edge
            var child = hv[i * 4 + 2]; var big = false
            while (child >= 0) { if (Imgproc.contourArea(contours[child]) >= minHole) big = true; child = hv[child * 4] }
            if (!big) continue
            val filled = Mat.zeros(m.size(), CvType.CV_8UC1)
            Imgproc.drawContours(filled, contours, i, Scalar(255.0), -1)
            // Disc pixels count only within the hull of the loop's own new ink: a disc
            // the loop runs round is inside it, the part of a disc it only grazes is not.
            val ink = Mat(); Core.bitwise_and(stroke, filled, ink)
            val pts = Mat(); Core.findNonZero(ink, pts); ink.release()
            val hullMask = Mat.zeros(m.size(), CvType.CV_8UC1)
            if (pts.rows() >= 3) {
                val p2 = MatOfPoint(pts)
                val idx = MatOfInt(); Imgproc.convexHull(p2, idx)
                val arr = p2.toArray(); val hull = MatOfPoint(*idx.toArray().map { arr[it] }.toTypedArray())
                Imgproc.fillPoly(hullMask, listOf(hull), Scalar(255.0))
            }
            pts.release()
            val drop = Mat(); Core.bitwise_not(hullMask, drop); Core.bitwise_and(drop, discMask, drop)
            val region = Mat(); Core.bitwise_not(drop, drop); Core.bitwise_and(filled, drop, region)
            drop.release(); hullMask.release()
            out += filled to region
        }
        return out
    }

    /**
     * Filled convex hulls of clusters of [ink] (pieces within [JOIN_MM] of each
     * other) that look like a loop with a gap: the hull encloses enough paper
     * and the ink runs along at least [MIN_OUTLINE_INKED] of its outline. New
     * caller-owned Mats.
     */
    internal fun openLoops(ink: Mat, oldSame: Mat, ppm: Double): List<Mat> {
        // Old ink of the same colour next to the new ink counts as part of the line: a
        // new loop drawn over an old one shows no change where the two run together.
        val bridge = Mat(); Imgproc.dilate(ink, bridge, ChangeDetector.disc((NEAR_MM * ppm).roundToInt() or 1))
        Core.bitwise_and(bridge, oldSame, bridge); Core.bitwise_or(bridge, ink, bridge)
        val joined = Mat(); Imgproc.dilate(bridge, joined, ChangeDetector.disc((JOIN_MM * ppm).roundToInt() or 1))
        val labels = Mat()
        val n = Imgproc.connectedComponents(joined, labels, 8, CvType.CV_32S)
        joined.release()
        if (n <= 1) { labels.release(); return emptyList() }
        val near = Mat(); Imgproc.dilate(bridge, near, ChangeDetector.disc((3 * ppm).roundToInt() or 1))
        bridge.release()
        val out = ArrayList<Mat>()
        for (k in 1 until n) {
            val cl = Mat(); Core.inRange(labels, Scalar(k.toDouble()), Scalar(k.toDouble()), cl)
            Core.bitwise_and(cl, ink, cl)
            val pts = Mat(); Core.findNonZero(cl, pts); cl.release()
            if (pts.rows() < 10) { pts.release(); continue }
            val p2 = MatOfPoint(pts); pts.release()
            val idx = MatOfInt(); Imgproc.convexHull(p2, idx)
            val arr = p2.toArray(); val hull = idx.toArray().map { arr[it] }
            val hullMat = MatOfPoint(*hull.toTypedArray())
            if (Imgproc.contourArea(hullMat) < MIN_OPEN_LOOP_MM2 * ppm * ppm) continue
            if (outlineInked(hull, near, ppm) < MIN_OUTLINE_INKED) continue
            val region = Mat.zeros(ink.size(), CvType.CV_8UC1)
            Imgproc.fillPoly(region, listOf(hullMat), Scalar(255.0))
            out += region
        }
        near.release(); labels.release()
        return out
    }

    /**
     * One loop can be read twice as two colours (a marker's shade differs on the
     * beige and on the black, or where it crosses an aim point). Loops whose
     * regions mostly overlap are one loop: keep the one with more ink, with the
     * joint region.
     */
    private fun mergeOverlapping(marks: List<InkMark>, regions: Map<Int, Mat>): Read {
        val loops = marks.filter { it.kind != InkKind.STRAY }.sortedByDescending { it.inkAreaMm2 }
        val gone = HashSet<Int>()
        val outRegions = HashMap(regions)
        for ((i, a) in loops.withIndex()) {
            if (a.id in gone) continue
            for (b in loops.drop(i + 1)) {
                if (b.id in gone) continue
                val ra = outRegions.getValue(a.id); val rb = outRegions.getValue(b.id)
                val both = Mat(); Core.bitwise_and(ra, rb, both)
                val shared = Core.countNonZero(both).toDouble(); both.release()
                if (shared < 0.6 * minOf(Core.countNonZero(ra), Core.countNonZero(rb))) continue
                Core.bitwise_or(ra, rb, ra)
                outRegions.remove(b.id)?.release(); gone += b.id
            }
        }
        return Read(marks.filter { it.id !in gone }, outRegions)
    }

    /** Share of a polygon's outline (walked in ~1 mm steps) where [near] is set. */
    private fun outlineInked(poly: List<Point>, near: Mat, ppm: Double): Double {
        var total = 0; var inked = 0
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[(i + 1) % poly.size]
            val steps = (hypot(b.x - a.x, b.y - a.y) / ppm).toInt().coerceAtLeast(1)
            for (t in 0 until steps) {
                val x = (a.x + (b.x - a.x) * t / steps).roundToInt().coerceIn(0, near.cols() - 1)
                val y = (a.y + (b.y - a.y) * t / steps).roundToInt().coerceIn(0, near.rows() - 1)
                total++
                if (near.get(y, x)[0] > 0) inked++
            }
        }
        return if (total == 0) 0.0 else inked.toDouble() / total
    }

    /**
     * A loop region that only part of the loop closed (e.g. the bottom of a loop
     * closed by an old loop of the same colour, while its top runs off where no
     * new ink shows) is widened to the convex hull of its stroke, if the line
     * (as [support] sees it) follows most of that hull's outline. Takes [region].
     */
    internal fun fillToHull(region: Mat, stroke: Mat, support: Mat, ppm: Double): Mat {
        val mine = Mat(); Core.bitwise_and(region, stroke, mine)
        val pts = Mat(); Core.findNonZero(mine, pts); mine.release()
        if (pts.rows() < 10) { pts.release(); return region }
        val p2 = MatOfPoint(pts); pts.release()
        val idx = MatOfInt(); Imgproc.convexHull(p2, idx)
        val arr = p2.toArray(); val hull = idx.toArray().map { arr[it] }
        val hullMat = MatOfPoint(*hull.toTypedArray())
        val hullArea = Imgproc.contourArea(hullMat)
        if (Core.countNonZero(region) >= 0.85 * hullArea || outlineInked(hull, support, ppm) < MIN_OUTLINE_INKED) return region
        Imgproc.fillPoly(region, listOf(hullMat), Scalar(255.0))
        return region
    }

    private const val JOIN_MM = 20.0
    private const val MIN_OPEN_LOOP_MM2 = 400.0
    private const val MIN_OUTLINE_INKED = 0.7

    /**
     * True if more than [MAX_EDGE_SHARE] of the outline of the on-paper part of
     * [region] is paper edge rather than ink: then it isn't a loop closed by
     * the edge but, say, a stroke cutting off a corner.
     */
    internal fun mostlyEdge(region: Mat, offCard: Mat, ppm: Double): Boolean {
        // Leave out the thin strip along the edge that only bridged the stroke's ends.
        val wide = Mat(); Imgproc.dilate(offCard, wide, ChangeDetector.disc((4 * ppm).roundToInt() or 1))
        val onPaper = Mat(); Core.bitwise_not(wide, onPaper); Core.bitwise_and(onPaper, region, onPaper)
        val inner = Mat(); Imgproc.erode(onPaper, inner, ChangeDetector.disc(3))
        val rim = Mat(); Core.subtract(onPaper, inner, rim)
        val edge = Mat(); Imgproc.dilate(offCard, edge, ChangeDetector.disc((5 * ppm).roundToInt() or 1)); Core.bitwise_and(edge, rim, edge)
        wide.release()
        val share = Core.countNonZero(edge).toDouble() / Core.countNonZero(rim).coerceAtLeast(1)
        onPaper.release(); inner.release(); rim.release(); edge.release()
        return share > MAX_EDGE_SHARE
    }

    private const val MAX_EDGE_SHARE = 0.45

    private fun outlineOf(region: Mat, box: ViewBox): List<PointMm> {
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(region.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val biggest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return emptyList()
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*biggest.toArray()), approx, 0.5 * box.pxPerMm, true)
        return approx.toArray().map { box.toMm(it.x, it.y) }
    }

    private fun angDiff(a: Double, b: Double): Double { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }
}
