package rangetarget.core.session

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.card.Homography
import rangetarget.core.card.RingFitter
import rangetarget.core.card.captioned
import rangetarget.core.geometry.PointMm
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The annotated pictures of the session pipeline. Same drawing code for the
 * desktop debug images and, later, the phone's confirm screen. All drawing is
 * on the flattened, top-down card view.
 */
object SessionDrawing {
    private val WHITE = Scalar(255.0, 255.0, 255.0)
    private val BLACK = Scalar(0.0, 0.0, 0.0)
    private val GREY = Scalar(140.0, 140.0, 140.0)
    private val RED = Scalar(0.0, 0.0, 230.0)
    private val GREEN = Scalar(0.0, 180.0, 0.0)
    private val CYAN = Scalar(230.0, 200.0, 0.0)
    private const val FONT = Imgproc.FONT_HERSHEY_SIMPLEX

    fun aimPoints(view: Mat, box: ViewBox, aims: List<AimPointReading>, existingHoles: Int): Mat {
        val out = view.clone()
        rings(out, box)
        for (a in aims) {
            val p = box.toPx(a.centreMm)
            Imgproc.circle(out, p, (a.diameterMm / 2 * box.pxPerMm + 6).roundToInt(), CYAN, 3, Imgproc.LINE_AA)
            Imgproc.drawMarker(out, p, CYAN, Imgproc.MARKER_CROSS, 24, 2)
            val unsure = a.flags.isNotEmpty()
            label(out, "${a.number ?: "?"}${if (unsure) "?" else ""}  (%.0f%%)".format(100 * a.numberConfidence),
                Point(p.x + a.diameterMm / 2 * box.pxPerMm + 12, p.y + 10), if (unsure) RED else BLACK, 1.1)
        }
        return captioned(out, "Baseline: ${aims.size} aim points found (cyan). Label = number read, and how sure (? = please confirm)",
            "Stickers: ${aims.count { it.style == AimPointStyle.STICKER }}, hand-drawn: ${aims.count { it.style == AimPointStyle.HAND_DRAWN }}. Holes already in the card: $existingHoles")
    }

    fun alignment(view: Mat, box: ViewBox, patches: List<PatchMatch>, s: AlignmentSummary): Mat {
        val out = view.clone()
        val k = 40.0 // arrows drawn 40× longer than the leftover error
        for (p in patches) {
            val a = box.toPx(p.centreMm)
            val b = Point(a.x + p.shiftMm.x * k * box.pxPerMm, a.y - p.shiftMm.y * k * box.pxPerMm)
            Imgproc.circle(out, a, 4, if (p.used) GREEN else RED, -1)
            Imgproc.arrowedLine(out, a, b, if (p.used) GREEN else RED, 2, Imgproc.LINE_AA, 0, 0.2)
        }
        return captioned(out, "Step 1: lined up with the previous photo using ${s.patchesUsed} patches (green; red = ignored, the patch changed)",
            "Correction on top of the ring fit: up to %.1f mm. Left over: %.2f mm typical, %.2f mm (95%%). Arrows show the left-over, 40x enlarged".format(
                s.largestCorrectionMm, s.leftoverMedianMm, s.leftoverP95Mm))
    }

    fun changes(view: Mat, box: ViewBox, ch: Changes, ink: InkReader.Read): Mat {
        val out = Mat()
        Core.addWeighted(view, 0.45, view, 0.0, 70.0, out)
        out.setTo(Scalar(80.0, 80.0, 80.0), ch.changed)
        out.setTo(Scalar(0.0, 0.0, 255.0), ch.holes)
        out.setTo(Scalar(255.0, 0.0, 255.0), ch.ink)
        for (m in ink.marks) {
            val c = if (m.kind == InkKind.STRAY) GREY else MarkerColours.bgr(m.colourRgb)
            poly(out, box, m.outlineMm, c, 3)
            val word = when (m.kind) { InkKind.LOOP -> "loop"; InkKind.LOOP_WITH_GAP -> "loop (gap)"; InkKind.LOOP_AT_EDGE -> "loop (edge)"; InkKind.STRAY -> "stray ink" }
            val top = m.outlineMm.maxByOrNull { it.y } ?: m.centreMm
            label(out, "${Assigner.pretty(m.colourName)} $word", Point(box.toPx(top).x - 60, box.toPx(top).y - 14), c, 0.9)
        }
        return captioned(out, "Step 2: what changed since the last photo. Red = new holes, magenta = new ink, dark grey = other small changes (ignored)",
            "Ink sorted by colour; closed loops outlined in their colour, stray ink in grey")
    }

    fun result(view: Mat, box: ViewBox, r: RoundResult, aims: List<AimPointReading>, ink: InkReader.Read): Mat {
        val out = Mat()
        Core.addWeighted(view, 0.7, view, 0.0, 60.0, out)
        rings(out, box)
        val ppm = box.pxPerMm
        for (a in aims) {
            val p = box.toPx(a.centreMm)
            Imgproc.circle(out, p, (a.diameterMm / 2 * ppm).roundToInt(), WHITE, 2, Imgproc.LINE_AA)
            Imgproc.drawMarker(out, p, WHITE, Imgproc.MARKER_CROSS, 30, 3)
        }
        for (m in ink.marks) poly(out, box, m.outlineMm, if (m.kind == InkKind.STRAY) GREY else MarkerColours.bgr(m.colourRgb), if (m.kind == InkKind.STRAY) 2 else 4)
        val shotById = r.shots.associateBy { it.id }
        for (g in r.groups) {
            val colour = MarkerColours.bgr(g.colourRgb)
            val shots = g.shotIds.mapNotNull { shotById[it] }
            val met = g.metrics
            if (met != null) {
                val c = box.toPx(met.centreMm)
                if (met.shots >= 2) Imgproc.circle(out, c, (met.meanRadiusMm * ppm).roundToInt(), colour, 2, Imgproc.LINE_AA)
                if (met.extremePairIds.size == 2) {
                    val a = shotById[met.extremePairIds[0]]; val b = shotById[met.extremePairIds[1]]
                    if (a != null && b != null) dashed(out, box.toPx(a.centreMm), box.toPx(b.centreMm), BLACK)
                }
                Imgproc.drawMarker(out, c, BLACK, Imgproc.MARKER_TILTED_CROSS, 34, 6)
                Imgproc.drawMarker(out, c, colour, Imgproc.MARKER_TILTED_CROSS, 30, 3)
            }
            for (s in shots) shot(out, box, s, colour)
            // Group label above its loop.
            val outline = r.ink.firstOrNull { it.id == g.loopId }?.outlineMm.orEmpty()
            val top = outline.maxByOrNull { it.y } ?: met?.centreMm ?: PointMm.ORIGIN
            val lines = buildList {
                add("${g.id.uppercase()}  aim ${g.aimPointNumber ?: "-"}  ${g.detectedShots}${(g.firedShots ?: g.expectedShots)?.let { "/$it" } ?: ""} shots")
                if (met != null && met.shots >= 2) add("ES %.0f  MR %.0f mm  (%.1f / %.1f mrad)".format(met.extremeSpreadMm, met.meanRadiusMm, met.extremeSpreadMrad, met.meanRadiusMrad))
                met?.offsetWords?.let { add(it) }
            }
            val at = box.toPx(top)
            lines.forEachIndexed { i, t -> label(out, t, Point(at.x - 150, at.y - 18 - (lines.size - 1 - i) * 34), BLACK, 0.85) }
        }
        for (s in r.shots.filter { it.groupId == null }) {
            shot(out, box, s, WHITE)
            val p = box.toPx(s.centreMm)
            Imgproc.circle(out, p, (s.diameterMm * ppm).roundToInt(), RED, 4, Imgproc.LINE_AA)
        }
        // Numbered flag tags.
        val allFlags = r.flags + r.groups.flatMap { it.flags }
        val tagged = allFlags.filter { it.atMm != null }
        tagged.forEachIndexed { i, f -> tag(out, box.toPx(f.atMm!!), (i + 1).toString()) }
        val notes = tagged.mapIndexed { i, f -> "${i + 1}. ${f.reason}" } + allFlags.filter { it.atMm == null }.map { "- ${it.reason}" }
        val shotsFired = r.groups.mapNotNull { it.firedShots ?: it.expectedShots }.takeIf { it.size == r.groups.size && it.isNotEmpty() }?.sum()
        val withNotes = withNotes(out, notes.ifEmpty { listOf("No flags: everything was assigned cleanly.") })
        return captioned(withNotes, "Round ${r.round}: ${r.shots.size} new holes${shotsFired?.let { " ($it fired)" } ?: ""} in ${r.groups.size} groups at %.0f m. Holes in their loop's colour; white with red ring = not in any loop".format(r.distanceM),
            "Cross = group centre, circle = mean radius, dashed line = extreme spread. Red numbers = flags, listed below")
    }

    // --- helpers -------------------------------------------------------------

    private fun shot(out: Mat, box: ViewBox, s: ShotReading, colour: Scalar) {
        val p = box.toPx(s.centreMm)
        val r = (s.diameterMm / 2 * box.pxPerMm).roundToInt()
        Imgproc.circle(out, p, r, colour, -1, Imgproc.LINE_AA)
        Imgproc.circle(out, p, r, if (s.flags.isEmpty()) BLACK else RED, 2, Imgproc.LINE_AA)
        val n = s.id.substringAfter('s').trimStart('0')
        label(out, n, Point(p.x + r + 2, p.y - r), BLACK, 0.6, box = false)
    }

    private fun rings(img: Mat, box: ViewBox) {
        RingFitter.drawRings(img, Homography.mmToTopDown(box.pxPerMm, box.left, box.top), Scalar(170.0, 170.0, 170.0), 1)
    }

    private fun poly(img: Mat, box: ViewBox, pts: List<PointMm>, c: Scalar, t: Int) {
        if (pts.size < 2) return
        Imgproc.polylines(img, listOf(MatOfPoint(*pts.map { box.toPx(it) }.toTypedArray())), true, c, t, Imgproc.LINE_AA)
    }

    private fun dashed(img: Mat, a: Point, b: Point, c: Scalar) {
        val n = max(2, (Math.hypot(b.x - a.x, b.y - a.y) / 10).toInt())
        for (i in 0 until n step 2) {
            val t0 = i.toDouble() / n; val t1 = (i + 1.0) / n
            Imgproc.line(img, Point(a.x + (b.x - a.x) * t0, a.y + (b.y - a.y) * t0), Point(a.x + (b.x - a.x) * t1, a.y + (b.y - a.y) * t1), c, 2, Imgproc.LINE_AA)
        }
    }

    private fun tag(img: Mat, p: Point, text: String) {
        val at = Point(p.x + 22, p.y - 22)
        Imgproc.circle(img, at, 20, RED, -1, Imgproc.LINE_AA)
        Imgproc.circle(img, at, 20, WHITE, 2, Imgproc.LINE_AA)
        val size = Imgproc.getTextSize(text, FONT, 0.8, 2, IntArray(1))
        Imgproc.putText(img, text, Point(at.x - size.width / 2, at.y + size.height / 2), FONT, 0.8, WHITE, 2, Imgproc.LINE_AA)
    }

    private fun label(img: Mat, text: String, p: Point, c: Scalar, scale: Double, box: Boolean = true) {
        val base = IntArray(1)
        val size = Imgproc.getTextSize(text, FONT, scale, 2, base)
        val x = p.x.coerceIn(4.0, img.cols() - size.width - 4); val y = p.y.coerceIn(size.height + 4, img.rows() - 4.0)
        if (box) {
            val r = Rect(Point(x - 4, y - size.height - 6), Point(x + size.width + 4, y + 6))
            val roi = img.submat(Rect(max(0, r.x), max(0, r.y), (r.width).coerceAtMost(img.cols() - max(0, r.x)), r.height.coerceAtMost(img.rows() - max(0, r.y))))
            Core.addWeighted(roi, 0.25, roi, 0.0, 190.0, roi)
        }
        Imgproc.putText(img, text, Point(x, y), FONT, scale, c, 2, Imgproc.LINE_AA)
    }

    /** Adds a band below the picture with numbered notes, wrapped to the width. */
    private fun withNotes(img: Mat, notes: List<String>): Mat {
        val scale = 0.9; val lineH = 36
        val maxChars = (img.cols() / (scale * 19)).toInt()
        val lines = notes.flatMap { wrap(it, maxChars) }
        val band = lineH * lines.size + 24
        val rows = img.rows()
        val out = Mat()
        Core.copyMakeBorder(img, out, 0, band, 0, 0, Core.BORDER_CONSTANT, Scalar(245.0, 245.0, 245.0))
        img.release()
        lines.forEachIndexed { i, t -> Imgproc.putText(out, t, Point(16.0, rows + (i + 1) * lineH.toDouble()), FONT, scale, BLACK, 2, Imgproc.LINE_AA) }
        return out
    }

    private fun wrap(s: String, n: Int): List<String> {
        val words = s.split(' '); val out = ArrayList<String>(); var cur = StringBuilder()
        for (w in words) {
            if (cur.length + w.length + 1 > n && cur.isNotEmpty()) { out += cur.toString(); cur = StringBuilder("    ") }
            if (cur.isNotEmpty() && !cur.endsWith(" ")) cur.append(' ')
            cur.append(w)
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }
}
