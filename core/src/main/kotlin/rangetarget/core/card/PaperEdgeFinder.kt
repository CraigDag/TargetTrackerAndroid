package rangetarget.core.card

import kotlinx.serialization.Serializable
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.PointMm
import rangetarget.core.image.Plane
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Side { TOP, BOTTOM, LEFT, RIGHT }

/**
 * A straight paper edge in card mm: for TOP/BOTTOM `y = offset + slope * x`,
 * for LEFT/RIGHT `x = offset + slope * y`.
 */
@Serializable
data class EdgeLine(val side: Side, val offsetMm: Double, val slope: Double, val points: Int, val inliers: Int) {
    fun at(t: Double): PointMm = when (side) {
        Side.TOP, Side.BOTTOM -> PointMm(t, offsetMm + slope * t)
        Side.LEFT, Side.RIGHT -> PointMm(offsetMm + slope * t, t)
    }

    /** Anticlockwise turn of the paper this edge implies, in degrees. */
    val turnDeg: Double get() = Math.toDegrees(when (side) {
        Side.TOP, Side.BOTTOM -> atan(slope)
        Side.LEFT, Side.RIGHT -> -atan(slope)
    })
}

class PaperEdges(
    val edges: Map<Side, EdgeLine>,
    /** Edge points found per side (mm), for the debug image. */
    val points: Map<Side, List<PointMm>>,
    val view: TopDown,
    val paperMask: Mat,
) {
    /** Anticlockwise turn of the paper in the frame used, averaged over the edges found. */
    val turnDeg: Double? get() = edges.values.takeIf { it.isNotEmpty() }?.let { es ->
        es.sumOf { it.turnDeg * it.inliers } / es.sumOf { it.inliers }
    }

    val widthMm: Double? get() {
        val l = edges[Side.LEFT] ?: return null; val r = edges[Side.RIGHT] ?: return null
        return r.offsetMm - l.offsetMm
    }
    val heightMm: Double? get() {
        val t = edges[Side.TOP] ?: return null; val b = edges[Side.BOTTOM] ?: return null
        return t.offsetMm - b.offsetMm
    }

    /** Paper corners (top-left, top-right, bottom-right, bottom-left) in card mm, if all four edges were found. */
    fun corners(): List<PointMm>? {
        val t = edges[Side.TOP] ?: return null; val b = edges[Side.BOTTOM] ?: return null
        val l = edges[Side.LEFT] ?: return null; val r = edges[Side.RIGHT] ?: return null
        return listOf(cross(t, l), cross(t, r), cross(b, r), cross(b, l))
    }

    /**
     * The paper outline traced from the edge points themselves (TL → TR → BR
     * → BL), following any curl, with the straight edge filling the corners
     * and gaps. Null unless all four edges were found.
     */
    fun outline(): List<PointMm>? {
        val c = corners() ?: return null
        val out = ArrayList<PointMm>()
        fun trace(side: Side, from: Double, to: Double) {
            val line = edges.getValue(side)
            val pts = points[side].orEmpty()
            val horizontal = side == Side.TOP || side == Side.BOTTOM
            val step = if (to > from) 2.0 else -2.0
            var t = from
            while ((step > 0 && t <= to) || (step < 0 && t >= to)) {
                val ideal = line.at(t)
                val near = pts.filter { p ->
                    val along = if (horizontal) p.x else p.y
                    val off = if (horizontal) p.y - ideal.y else p.x - ideal.x
                    abs(along - t) <= 8.0 && abs(off) <= 5.0
                }.map { if (horizontal) it.y else it.x }.sorted()
                val o = if (near.size >= 5) near[near.size / 2] else if (horizontal) ideal.y else ideal.x
                out += if (horizontal) PointMm(t, o) else PointMm(o, t)
                t += step
            }
        }
        trace(Side.TOP, c[0].x, c[1].x)
        trace(Side.RIGHT, c[1].y, c[2].y)
        trace(Side.BOTTOM, c[2].x, c[3].x)
        trace(Side.LEFT, c[3].y, c[0].y)
        return out
    }

    private fun cross(h: EdgeLine, v: EdgeLine): PointMm {
        // y = a + b x and x = c + d y
        val y = (h.offsetMm + h.slope * v.offsetMm) / (1 - h.slope * v.slope)
        return PointMm(v.offsetMm + v.slope * y, y)
    }
}

/**
 * Finds the four straight paper edges, working in a flattened view of the card.
 * Paper is told from the floor by colour: the card is a warm beige and the
 * floor a cool grey, close in brightness but different in hue. Edges are found
 * by walking outward from well inside the paper and fitting a straight line to
 * where the paper stops, ignoring points that disagree (holes on the edge, tears).
 */
object PaperEdgeFinder {
    private const val PX_PER_MM = 2.0
    private const val EXTENT_MM = 360.0
    /** Start each walk this far out from the centre: inside the paper on every side. */
    private const val START_MM = 200.0

    fun find(photo: Mat, cardToPhoto: CardToPhoto): PaperEdges {
        val view = TopDown.warp(photo, cardToPhoto, PX_PER_MM, -EXTENT_MM, EXTENT_MM, -EXTENT_MM, EXTENT_MM)
        val mask = paperMask(view.image, paperColour(view))
        val plane = Plane.of(mask)
        val points = HashMap<Side, List<PointMm>>()
        val edges = HashMap<Side, EdgeLine>()
        for (side in Side.entries) {
            val pts = walk(plane, view, side)
            points[side] = pts
            fitLine(side, pts)?.let { edges[side] = it }
        }
        return PaperEdges(edges, points, view, mask)
    }

    /** Typical paper colour in OpenCV 8-bit Lab, measured in the plain band between the black and the 1 ring. */
    data class PaperColour(val l: Int, val a: Int, val b: Int)

    fun paperColour(view: TopDown): PaperColour {
        val lab = labPlanes(view.image)
        val ls = ArrayList<Int>(); val As = ArrayList<Int>(); val bs = ArrayList<Int>()
        var r = 115.0
        while (r < 235.0) {
            for (k in 0 until 360) {
                val t = Math.toRadians(k + 0.5)
                val p = view.toPx(PointMm(r * kotlin.math.cos(t), r * kotlin.math.sin(t)))
                if (!lab[0].inside(p.x, p.y)) continue
                val x = p.x.roundToInt(); val y = p.y.roundToInt()
                val l = lab[0][x, y]
                if (l < 100) continue // print, holes
                ls += l; As += lab[1][x, y]; bs += lab[2][x, y]
            }
            r += 7.0
        }
        fun med(v: List<Int>) = if (v.isEmpty()) 0 else v.sorted()[v.size / 2]
        return PaperColour(med(ls), med(As), med(bs))
    }

    /**
     * Paper pixels (the card's beige, or dark print) as 255. Beige is judged
     * on colour, not brightness, so shadows across the card don't matter much:
     * the grey floor is much bluer and yellow floor paint much yellower.
     */
    fun paperMask(bgr: Mat, paper: PaperColour): Mat {
        val lab = labPlanes(bgr)
        val n = lab[0].data.size
        val out = ByteArray(n)
        for (i in 0 until n) {
            val l = lab[0].data[i].toInt() and 0xFF
            val a = lab[1].data[i].toInt() and 0xFF
            val b = lab[2].data[i].toInt() and 0xFF
            val beige = abs(b - paper.b) <= 8 && abs(a - paper.a) <= 7 && l >= paper.l - 60
            val ink = l in 1..60
            out[i] = if (beige || ink) -1 else 0
        }
        val m = Plane(lab[0].width, lab[0].height, out).toMat()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(7.0, 7.0))
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_OPEN, k)
        Imgproc.morphologyEx(m, m, Imgproc.MORPH_CLOSE, k)
        return m
    }

    private fun labPlanes(bgr: Mat): List<Plane> {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val parts = ArrayList<Mat>()
        org.opencv.core.Core.split(lab, parts)
        return parts.map { Plane.of(it).also { _ -> it.release() } }.also { lab.release() }
    }

    private fun walk(mask: Plane, view: TopDown, side: Side): List<PointMm> {
        val out = ArrayList<PointMm>()
        val run = (6 * PX_PER_MM).roundToInt()
        var t = -START_MM
        while (t <= START_MM) {
            var s = START_MM
            var inside = 0; var outside = 0; var found: Double? = null
            while (s < EXTENT_MM - 1) {
                val p = point(side, t, s)
                val px = view.toPx(p)
                if (!mask.inside(px.x, px.y)) break
                val on = mask[px.x.roundToInt(), px.y.roundToInt()] != 0
                if (on) { inside++; outside = 0 } else outside++
                if (outside >= run && inside > 10) { found = s - (run - 1) / PX_PER_MM; break }
                s += 1 / PX_PER_MM
            }
            if (found != null) out += point(side, t, found)
            t += 1.0
        }
        return out
    }

    private fun point(side: Side, along: Double, out: Double) = when (side) {
        Side.TOP -> PointMm(along, out)
        Side.BOTTOM -> PointMm(along, -out)
        Side.RIGHT -> PointMm(out, along)
        Side.LEFT -> PointMm(-out, along)
    }

    /** RANSAC straight line through the edge points, then least squares on the agreeing ones. */
    fun fitLine(side: Side, pts: List<PointMm>, toleranceMm: Double = 1.5): EdgeLine? {
        if (pts.size < 40) return null
        val horizontal = side == Side.TOP || side == Side.BOTTOM
        val u = pts.map { if (horizontal) it.x else it.y }
        val v = pts.map { if (horizontal) it.y else it.x }
        val rnd = Random(7)
        var bestA = 0.0; var bestB = 0.0; var bestCount = -1
        repeat(400) {
            val i = rnd.nextInt(u.size); val j = rnd.nextInt(u.size)
            if (abs(u[i] - u[j]) < 30) return@repeat
            val b = (v[j] - v[i]) / (u[j] - u[i]); val a = v[i] - b * u[i]
            val c = u.indices.count { abs(v[it] - (a + b * u[it])) < toleranceMm }
            if (c > bestCount) { bestCount = c; bestA = a; bestB = b }
        }
        val ins = u.indices.filter { abs(v[it] - (bestA + bestB * u[it])) < toleranceMm }
        if (ins.size < 30 || ins.size < 0.4 * pts.size) return null
        val mu = ins.map { u[it] }.average(); val mv = ins.map { v[it] }.average()
        val sxx = ins.sumOf { (u[it] - mu) * (u[it] - mu) }
        val sxy = ins.sumOf { (u[it] - mu) * (v[it] - mv) }
        val b = sxy / sxx; val a = mv - b * mu
        return EdgeLine(side, a, b, pts.size, ins.size)
    }

    /** Debug image: flattened card, paper-coloured area tinted, edge points and fitted edges. */
    fun drawDebug(e: PaperEdges): Mat {
        val out = e.view.image.clone()
        val t = max(1, (out.cols() / 700.0).roundToInt())
        val tint = Mat(out.size(), out.type(), org.opencv.core.Scalar(0.0, 160.0, 0.0))
        val blended = Mat()
        org.opencv.core.Core.addWeighted(out, 0.75, tint, 0.25, 0.0, blended)
        blended.copyTo(out, e.paperMask)
        for ((side, pts) in e.points) {
            val line = e.edges[side]
            pts.forEach { p ->
                val ok = line != null && abs(dist(line, p)) < 1.5
                Imgproc.circle(out, e.view.toPx(p), t, if (ok) CYAN else RED, -1)
            }
            line?.let { l ->
                Imgproc.line(out, e.view.toPx(l.at(-330.0)), e.view.toPx(l.at(330.0)), YELLOW, t)
            }
        }
        val size = if (e.widthMm != null && e.heightMm != null) "paper %.0f x %.0f mm".format(e.widthMm, e.heightMm) else "paper size unknown (edge missing)"
        return captioned(out, "Step 3: paper edges (flattened view). Green tint = paper colour; yellow = fitted edges", "$size; card turned %.2f deg in this view; edges found: %s".format(e.turnDeg ?: 0.0,
            e.edges.keys.joinToString { it.name.lowercase() }))
    }

    private fun dist(l: EdgeLine, p: PointMm) = when (l.side) {
        Side.TOP, Side.BOTTOM -> p.y - (l.offsetMm + l.slope * p.x)
        Side.LEFT, Side.RIGHT -> p.x - (l.offsetMm + l.slope * p.y)
    }

}
