package rangetarget.core.holes

import kotlinx.serialization.Serializable
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import rangetarget.core.card.TopDown
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import rangetarget.core.image.Plane
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
enum class HoleZone { BEIGE, BLACK, BLACK_EDGE }

/** One detected hole (or one estimated hole inside a merged blob). */
@Serializable
data class Hole(
    val id: Int,
    val centreMm: PointMm,
    /** Equivalent diameter of the blob this hole came from, in mm. */
    val blobDiameterMm: Double,
    val blobAreaMm2: Double,
    val zone: HoleZone,
    /** Ring the centre falls in (centre-based, preliminary; see docs/SPEC-ISSUES.md item 6). */
    val ringByCentre: Int,
    /** Holes sharing a merged blob share this number; null for a clean single hole. */
    val blobId: Int?,
    /** Estimated number of shots in the blob this hole came from. */
    val shotsInBlob: Int,
    val flags: List<FlagCode>,
) {
    val confident: Boolean get() = flags.isEmpty()
}

/** A region too shot-out to separate into holes. */
@Serializable
data class UnresolvableCluster(
    val centreMm: PointMm,
    val radiusMm: Double,
    /** Paper missing inside the region, in mm². */
    val missingAreaMm2: Double,
    /** Missing area divided by a typical hole's area. A floor, not a count: overlapping shots hide each other. */
    val roughShotsAtLeast: Int,
    val reason: String,
)

class HoleDetection(
    val holes: List<Hole>,
    val clusters: List<UnresolvableCluster>,
    val typicalHoleAreaMm2: Double,
    /** Raw "not paper" pixels before thin things (lines, pen, numbers) are removed. */
    val rawMask: Mat,
    /** What is left after removing thin things: the hole blobs. */
    val holeMask: Mat,
)

/**
 * Finds holes in the flattened card (Phase 1, step 3).
 *
 * The floor shows through each hole. On the beige it looks bluish-grey and
 * darker than the paper; inside the black it looks lighter than the print and
 * not beige. Each pixel is compared with the paper or black around it (so
 * shadows and glare matter less), then anything thinner than about 3.5 mm is
 * removed: ring lines, pen strokes and printed numbers are all thinner than a
 * hole. What remains is grouped into blobs; a blob much bigger than a typical
 * hole is treated as several overlapping holes and flagged.
 */
object HoleDetector {
    /** Anything thinner than this is not a hole (lines, pen, print). */
    const val THIN_MM = 3.5
    /** The same inside the black, where creases and glare streaks are wider. */
    const val THIN_BLACK_MM = 5.0
    /** Smallest blob kept as a hole, in mm². */
    const val MIN_AREA_MM2 = 10.0
    /** Size of the neighbourhood used to judge the local paper/print colour. */
    private const val BACKGROUND_MM = 31
    private const val BACKGROUND_BLACK_MM = 15
    /**
     * Inside the black, floor and torn fibres are grey or bluish (Lab b ≤ ~128)
     * while the printed numbers and ring lines are beige (b ≈ 136+). Pixels
     * above this are treated as print.
     */
    private const val NEUTRAL_B_MAX = 130

    fun detect(view: TopDown, paperOutline: List<PointMm>, exclusions: List<UnresolvableCluster> = emptyList()): HoleDetection {
        val ppm = view.pxPerMm
        val lab = Mat().also { Imgproc.cvtColor(view.image, it, Imgproc.COLOR_BGR2Lab) }
        val parts = ArrayList<Mat>().also { Core.split(lab, it) }
        lab.release()
        val l = Plane.of(parts[0]); val b = Plane.of(parts[2])

        val w = l.width; val h = l.height
        val zone = ByteArray(w * h) // 0 outside paper, 1 beige zone, 2 black zone
        val paperPoly = MatOfPoint(*paperOutline.map { view.toPx(shrink(it, 0.7)) }.toTypedArray())
        val paperMask = Mat.zeros(h, w, CvType.CV_8UC1)
        Imgproc.fillPoly(paperMask, listOf(paperPoly), Scalar(255.0))
        val inPaper = Plane.of(paperMask)
        val rBlack = CardGeometry.blackRadiusMm
        for (y in 0 until h) for (x in 0 until w) {
            if (inPaper[x, y] == 0) continue
            val p = view.toMm(x + 0.5, y + 0.5)
            zone[y * w + x] = if (p.radius < rBlack) 2 else 1
        }

        val paperL = median(l, zone, 1); val blackL = median(l, zone, 2); val paperB = median(b, zone, 1)
        // The black uses a smaller neighbourhood: light bands along fold creases
        // there are about 10 mm wide and must count as background, not holes.
        val bgL = background(l, zone, ppm, mapOf(1.toByte() to paperL, 2.toByte() to blackL),
            mapOf(1.toByte() to BACKGROUND_MM, 2.toByte() to BACKGROUND_BLACK_MM))
        val bgB = background(b, zone, ppm, mapOf(1.toByte() to paperB, 2.toByte() to paperB),
            mapOf(1.toByte() to BACKGROUND_MM, 2.toByte() to BACKGROUND_BLACK_MM))

        val raw = ByteArray(w * h)
        val rawBlack = ByteArray(w * h)
        for (i in 0 until w * h) {
            val z = zone[i].toInt()
            if (z == 0) continue
            val li = l.data[i].toInt() and 0xFF; val bi = b.data[i].toInt() and 0xFF
            val lb = bgL.data[i].toInt() and 0xFF; val bb = bgB.data[i].toInt() and 0xFF
            if (z == 1) {
                // Floor is bluer than the paper, or the hole is in shadow.
                if (bi < bb - 8 || li < 0.68 * lb) raw[i] = -1
            } else {
                // Inside the black a hole shows lighter floor and torn fibres (not
                // the beige print of ring lines and numbers), or a deep shadow
                // darker than the print. The mix is speckled, so it is closed below.
                if ((li > lb + 22 && bi <= NEUTRAL_B_MAX) || li < lb - 14) rawBlack[i] = -1
            }
        }
        // The black: close the speckle, then remove anything thinner than
        // THIN_BLACK_MM. That is a little more than on the beige because fold
        // creases and glare streaks in the black look like light lines.
        val blackMask = Plane(w, h, rawBlack).toMat()
        val c = (2.0 * ppm).roundToInt() or 1
        Imgproc.morphologyEx(blackMask, blackMask, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(c.toDouble(), c.toDouble())))
        val kb = (THIN_BLACK_MM * ppm).roundToInt() or 1
        val blackHoles = Mat()
        Imgproc.morphologyEx(blackMask, blackHoles, Imgproc.MORPH_OPEN,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(kb.toDouble(), kb.toDouble())))
        val blackZone = Plane(w, h, ByteArray(w * h) { if (zone[it].toInt() == 2) -1 else 0 }).toMat()
        Core.bitwise_and(blackMask, blackZone, blackMask)
        Core.bitwise_and(blackHoles, blackZone, blackHoles)

        // The beige: remove anything thinner than THIN_MM (ring lines, pen, numbers).
        val beigeMask = Plane(w, h, raw).toMat()
        val k = (THIN_MM * ppm).roundToInt() or 1
        val holeMask = Mat()
        Imgproc.morphologyEx(beigeMask, holeMask, Imgproc.MORPH_OPEN,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        // A hole across the black edge is split between the two; join the halves back.
        Core.bitwise_or(holeMask, blackHoles, holeMask)
        val j = (1.0 * ppm).roundToInt() or 1
        Imgproc.morphologyEx(holeMask, holeMask, Imgproc.MORPH_CLOSE,
            Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(j.toDouble(), j.toDouble())))
        val rawMask = Mat()
        Core.bitwise_or(beigeMask, blackMask, rawMask)
        blackMask.release(); blackHoles.release(); blackZone.release(); beigeMask.release()

        // Blobs.
        val labels = Mat(); val stats = Mat(); val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(holeMask, labels, stats, cents, 8, CvType.CV_32S)
        val lab32 = IntArray(w * h).also { labels.get(0, 0, it) }
        val st = IntArray(n * 5).also { stats.get(0, 0, it) }
        val ct = DoubleArray(n * 2).also { cents.get(0, 0, it) }
        val pxArea = 1 / (ppm * ppm)

        data class Blob(val label: Int, val areaMm2: Double, val centre: PointMm, val solidity: Double, val touchesEdge: Boolean)
        val borderPlane = edgeBand(paperMask, ppm)
        val blobs = (1 until n).mapNotNull { i ->
            val area = st[i * 5 + 4] * pxArea
            if (area < MIN_AREA_MM2) return@mapNotNull null
            val c = view.toMm(ct[i * 2], ct[i * 2 + 1])
            val edge = touchesBorder(lab32, w, st, i, borderPlane)
            // A long thin strip along the paper edge is floor showing where the
            // paper edge curls or isn't perfectly straight, not a hole.
            val bw = st[i * 5 + 2].toDouble(); val bh = st[i * 5 + 3].toDouble()
            if (edge && max(bw, bh) > 2.5 * minOf(bw, bh)) return@mapNotNull null
            Blob(i, area, c, solidity(lab32, w, st, i), edge)
        }

        // Sticker / heavy-overlap regions: everything inside becomes the cluster.
        val inCluster = { p: PointMm -> exclusions.any { hypot(p.x - it.centreMm.x, p.y - it.centreMm.y) < it.radiusMm } }
        val free = blobs.filter { !inCluster(it.centre) }
        // A typical single hole, measured separately on the beige and in the black:
        // in the black the torn white rim counts as hole, so holes look bigger.
        fun typicalArea(sel: (Blob) -> Boolean, fallback: Double): Double {
            val singles = free.filter { sel(it) && it.solidity > 0.85 && it.areaMm2 in 15.0..200.0 && !it.touchesEdge }
                .map { it.areaMm2 }.sorted()
            return if (singles.size >= 5) singles[singles.size / 2] else fallback
        }
        val typical = typicalArea({ it.centre.radius > rBlack + 5 }, PI * 4.5 * 4.5)
        val typicalBlack = typicalArea({ it.centre.radius < rBlack - 5 }, typical)

        val clusters = exclusions.map { ex ->
            val missing = blobs.filter { inCluster(it.centre) && hypot(it.centre.x - ex.centreMm.x, it.centre.y - ex.centreMm.y) < ex.radiusMm }
                .sumOf { it.areaMm2 }
            ex.copy(missingAreaMm2 = missing, roughShotsAtLeast = (missing / typical).roundToInt())
        }

        val holes = ArrayList<Hole>()
        var nextBlob = 1
        for (blob in free.sortedWith(compareByDescending<Blob> { it.centre.y }.thenBy { it.centre.x })) {
            val ratio = blob.areaMm2 / (if (blob.centre.radius < rBlack) typicalBlack else typical)
            val shots = if (ratio < 1.6) 1 else max(2, ratio.roundToInt())
            val flags = ArrayList<FlagCode>()
            if (shots > 1) flags += FlagCode.OVERLAPPING_HOLES
            if (blob.touchesEdge) flags += FlagCode.HOLE_ON_PAPER_EDGE
            if (shots == 1 && (ratio < 0.45 || blob.solidity < 0.75)) flags += FlagCode.HOLE_UNCERTAIN
            if (CardGeometry.printedMarks.any { hypot(blob.centre.x - it.centreMm.x, blob.centre.y - it.centreMm.y) < it.radiusMm }
                && FlagCode.HOLE_UNCERTAIN !in flags) flags += FlagCode.HOLE_UNCERTAIN
            val centres = if (shots == 1) listOf(blob.centre) else split(lab32, w, st, blob.label, shots, view)
            val blobId = if (shots > 1) nextBlob++ else null
            for (c in centres) {
                val r = c.radius
                val z = when {
                    kotlin.math.abs(r - rBlack) < sqrt(blob.areaMm2 / PI) -> HoleZone.BLACK_EDGE
                    r < rBlack -> HoleZone.BLACK
                    else -> HoleZone.BEIGE
                }
                holes += Hole(0, c, 2 * sqrt(blob.areaMm2 / PI), blob.areaMm2, z, CardGeometry.ringAt(c), blobId, shots, flags)
            }
        }
        val numbered = holes.sortedWith(compareByDescending<Hole> { it.centreMm.y.roundToInt() / 10 }.thenBy { it.centreMm.x })
            .mapIndexed { i, hole -> hole.copy(id = i + 1) }

        parts.forEach { it.release() }; labels.release(); stats.release(); cents.release(); paperMask.release()
        return HoleDetection(numbered, clusters, typical, rawMask, holeMask)
    }

    private fun shrink(p: PointMm, mm: Double): PointMm {
        val r = p.radius
        return if (r < 1e-9) p else PointMm(p.x * (r - mm) / r, p.y * (r - mm) / r)
    }

    private fun median(p: Plane, zone: ByteArray, z: Int): Int {
        val hist = IntArray(256); var n = 0
        for (i in zone.indices) if (zone[i].toInt() == z) { hist[p.data[i].toInt() and 0xFF]++; n++ }
        var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc >= n / 2) return v }
        return 128
    }

    /**
     * Local typical value per zone: the median over a neighbourhood of [windowMm],
     * computed separately for the beige and the black so the black edge doesn't
     * blur into the paper. Pixels of the other zone are filled with that
     * zone's overall median first.
     */
    private fun background(p: Plane, zone: ByteArray, ppm: Double, fill: Map<Byte, Int>, windowMm: Map<Byte, Int>): Plane {
        val out = ByteArray(p.data.size)
        val scale = 1.0 / ppm // work at 1 px/mm
        for ((z, other) in fill) {
            val filled = ByteArray(p.data.size) { i -> if (zone[i] == z) p.data[i] else other.toByte() }
            val src = Plane(p.width, p.height, filled).toMat()
            val small = Mat()
            Imgproc.resize(src, small, Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.medianBlur(small, small, windowMm.getValue(z) or 1)
            val big = Mat()
            Imgproc.resize(small, big, Size(p.width.toDouble(), p.height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val bp = Plane.of(big)
            for (i in out.indices) if (zone[i] == z) out[i] = bp.data[i]
            src.release(); small.release(); big.release()
        }
        return Plane(p.width, p.height, out)
    }

    /** A thin band just inside the paper outline: blobs touching it are cut by the edge. */
    private fun edgeBand(paperMask: Mat, ppm: Double): Plane {
        val eroded = Mat()
        val k = (1.5 * ppm).roundToInt() * 2 + 1
        Imgproc.erode(paperMask, eroded, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(k.toDouble(), k.toDouble())))
        val band = Mat()
        Core.subtract(paperMask, eroded, band)
        return Plane.of(band).also { eroded.release(); band.release() }
    }

    private fun touchesBorder(lab: IntArray, w: Int, st: IntArray, i: Int, band: Plane): Boolean {
        val x0 = st[i * 5]; val y0 = st[i * 5 + 1]; val bw = st[i * 5 + 2]; val bh = st[i * 5 + 3]
        for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) if (lab[y * w + x] == i && band[x, y] != 0) return true
        return false
    }

    private fun solidity(lab: IntArray, w: Int, st: IntArray, i: Int): Double {
        val x0 = st[i * 5]; val y0 = st[i * 5 + 1]; val bw = st[i * 5 + 2]; val bh = st[i * 5 + 3]
        val pts = ArrayList<Point>()
        for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) if (lab[y * w + x] == i) {
            // Boundary pixels are enough for the hull.
            val edge = x == x0 || y == y0 || x == x0 + bw - 1 || y == y0 + bh - 1 ||
                lab[y * w + x - 1] != i || lab[y * w + x + 1] != i || lab[(y - 1) * w + x] != i || lab[(y + 1) * w + x] != i
            if (edge) pts += Point(x.toDouble(), y.toDouble())
        }
        if (pts.size < 5) return 1.0
        val mp = MatOfPoint(*pts.toTypedArray())
        val hullIdx = MatOfInt()
        Imgproc.convexHull(mp, hullIdx)
        val hull = MatOfPoint(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        val hullArea = Imgproc.contourArea(hull) + 0.5 * pts.size.coerceAtMost(4 * (bw + bh)) // pixel-centre hull underestimates
        return (st[i * 5 + 4] / hullArea).coerceAtMost(1.0)
    }

    /** Splits a merged blob into [k] hole centres by clustering its pixels. Centres are approximate. */
    private fun split(lab: IntArray, w: Int, st: IntArray, i: Int, k: Int, view: TopDown): List<PointMm> {
        val x0 = st[i * 5]; val y0 = st[i * 5 + 1]; val bw = st[i * 5 + 2]; val bh = st[i * 5 + 3]
        val pts = ArrayList<Float>()
        for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) if (lab[y * w + x] == i) { pts += x.toFloat(); pts += y.toFloat() }
        val data = Mat(pts.size / 2, 2, CvType.CV_32F).also { it.put(0, 0, pts.toFloatArray()) }
        val bestLabels = Mat(); val centers = Mat()
        Core.kmeans(data, k, bestLabels, TermCriteria(TermCriteria.EPS + TermCriteria.COUNT, 50, 0.1), 5, Core.KMEANS_PP_CENTERS, centers)
        val c = FloatArray(k * 2).also { centers.get(0, 0, it) }
        data.release(); bestLabels.release(); centers.release()
        return (0 until k).map { view.toMm(c[it * 2] + 0.5, c[it * 2 + 1] + 0.5) }
    }
}
