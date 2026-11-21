package rangetarget.core.session

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the paper is in the flattened view, and which part is beige and which
 * is the black aiming area. Fixed for a session (from the baseline photo).
 */
class CardZones(val box: ViewBox, paperOutlineMm: List<PointMm>, edgeMm: Double = EDGE_MM) {
    val width = box.widthPx
    val height = box.heightPx
    /** 255 on the paper (a little inside its edge), 0 elsewhere. */
    val paper: Mat
    /** Per pixel: 0 off the paper, 1 beige, 2 black. */
    val zone: ByteArray

    init {
        paper = Mat.zeros(height, width, CvType.CV_8UC1)
        val shrunk = paperOutlineMm.map { p -> val r = p.radius; if (r < 1e-9) p else PointMm(p.x * (r - edgeMm) / r, p.y * (r - edgeMm) / r) }
        Imgproc.fillPoly(paper, listOf(MatOfPoint(*shrunk.map { box.toPx(it) }.toTypedArray())), Scalar(255.0))
        val inPaper = ByteArray(width * height).also { paper.get(0, 0, it) }
        val rb = CardGeometry.blackRadiusMm
        zone = ByteArray(width * height) { i ->
            if (inPaper[i].toInt() == 0) 0 else {
                val p = box.toMm(i % width + 0.5, i / width + 0.5)
                if (p.radius < rb) 2 else 1
            }
        }
    }

    fun release() = paper.release()

    companion object {
        /** Stay this far inside the paper edge: the edge itself moves and frays between photos. */
        const val EDGE_MM = 2.0
        /** With only the nominal paper size to go on, keep further in: the real edge may be a few mm off. */
        const val NOMINAL_EDGE_MM = 10.0

        fun nominalPaper(): List<PointMm> {
            val x = CardGeometry.PAPER_WIDTH_MM / 2; val y = CardGeometry.PAPER_HEIGHT_MM / 2
            return listOf(PointMm(-x, y), PointMm(x, y), PointMm(x, -y), PointMm(-x, -y))
        }
    }
}

/**
 * Evens out the light. Each photo's paper (and black) is divided by its own
 * smoothly varying brightness and colour, so two photos taken in different
 * light end up with the same paper colour and can be compared pixel by pixel.
 */
object Lighting {
    // Wide enough that a 30 mm sticker or a shot-out group never fills half of it.
    private const val WINDOW_MM = 71

    /** Typical paper and black colour (BGR) of a view, used as the target for all later photos. */
    class Reference(val beige: DoubleArray, val black: DoubleArray)

    fun reference(bgr: Mat, zones: CardZones): Reference {
        val ch = ArrayList<Mat>().also { Core.split(bgr, it) }
        val r = Reference(DoubleArray(3) { median(ch[it], zones.zone, 1).toDouble() }, DoubleArray(3) { median(ch[it], zones.zone, 2).toDouble() })
        ch.forEach { it.release() }
        return r
    }

    /** Returns a new BGR image with the light evened out to [ref]. */
    fun normalise(bgr: Mat, zones: CardZones, ref: Reference): Mat {
        val ch = ArrayList<Mat>().also { Core.split(bgr, it) }
        val zone = zones.zone
        val outCh = ch.mapIndexed { c, m ->
            val src = ByteArray(zones.width * zones.height).also { m.get(0, 0, it) }
            val out = src.copyOf()
            for (z in 1..2) {
                val med = median(m, zone, z)
                val filled = ByteArray(src.size) { i -> if (zone[i].toInt() == z) src[i] else med.toByte() }
                // Rough light from a wide median, then refined from "plain" pixels only
                // (close to the rough level: no holes, ink or print), averaged with a soft
                // weight. The refinement follows the light right into the paper corners,
                // where the wide median sees mostly off-card filler.
                val rough = smooth(filled, zones.width, zones.height, zones.box.pxPerMm)
                val plain = FloatArray(src.size) { i ->
                    if (zone[i].toInt() != z) 0f else {
                        val v = src[i].toInt() and 0xFF; val r = rough[i].toInt() and 0xFF
                        val tol = if (z == 1) 0.22 * r else 0.5 * r + 12
                        if (kotlin.math.abs(v - r) < tol) 1f else 0f
                    }
                }
                val field = weightedSmooth(src, plain, rough, zones.width, zones.height, zones.box.pxPerMm)
                val target = if (z == 1) ref.beige[c] else ref.black[c]
                for (i in src.indices) if (zone[i].toInt() == z) {
                    val f = field[i].coerceAtLeast(4f)
                    out[i] = ((src[i].toInt() and 0xFF) * target / f).roundToInt().coerceIn(0, 255).toByte()
                }
            }
            Mat(zones.height, zones.width, CvType.CV_8UC1).also { it.put(0, 0, out) }
        }
        val merged = Mat()
        Core.merge(outCh, merged)
        ch.forEach { it.release() }; outCh.forEach { it.release() }
        return merged
    }

    /** Large median at 1 px/mm, scaled back up: the slowly varying light, ignoring holes and marks. */
    private fun smooth(data: ByteArray, w: Int, h: Int, ppm: Double): ByteArray {
        val src = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, data) }
        val small = Mat()
        Imgproc.resize(src, small, Size(), 1 / ppm, 1 / ppm, Imgproc.INTER_AREA)
        Imgproc.medianBlur(small, small, WINDOW_MM)
        val big = Mat()
        Imgproc.resize(small, big, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val out = ByteArray(w * h).also { big.get(0, 0, it) }
        src.release(); small.release(); big.release()
        return out
    }

    /** Average of [src] over pixels with weight [w], blurred ~15 mm, at 1 px/mm; [fallback] where there are none. */
    private fun weightedSmooth(src: ByteArray, w: FloatArray, fallback: ByteArray, width: Int, height: Int, ppm: Double): FloatArray {
        val sw = FloatArray(src.size) { (src[it].toInt() and 0xFF) * w[it] }
        fun down(a: FloatArray): Mat {
            val m = Mat(height, width, CvType.CV_32FC1).also { it.put(0, 0, a) }
            val small = Mat()
            Imgproc.resize(m, small, Size(), 1 / ppm, 1 / ppm, Imgproc.INTER_AREA)
            Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), 15.0)
            val big = Mat()
            Imgproc.resize(small, big, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            m.release(); small.release()
            return big
        }
        val a = down(sw); val b = down(w)
        val av = FloatArray(src.size).also { a.get(0, 0, it) }; val bv = FloatArray(src.size).also { b.get(0, 0, it) }
        a.release(); b.release()
        return FloatArray(src.size) { i -> if (bv[i] > 0.03f) av[i] / bv[i] else (fallback[i].toInt() and 0xFF).toFloat() }
    }

    private fun median(m: Mat, zone: ByteArray, z: Int): Int {
        val d = ByteArray(zone.size).also { m.get(0, 0, it) }
        val hist = IntArray(256); var n = 0
        for (i in zone.indices) if (zone[i].toInt() == z) { hist[d[i].toInt() and 0xFF]++; n++ }
        var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc > n / 2) return v }
        return 128
    }
}

/** One photo's flattened view in Lab colour, light evened out: what diffing works on. */
class LabView(val width: Int, val height: Int, val lab: ByteArray) {
    fun l(i: Int) = lab[3 * i].toInt() and 0xFF
    fun a(i: Int) = (lab[3 * i + 1].toInt() and 0xFF) - 128
    fun b(i: Int) = (lab[3 * i + 2].toInt() and 0xFF) - 128
    fun chroma(i: Int) = hypot(a(i).toDouble(), b(i).toDouble())

    companion object {
        fun of(normalisedBgr: Mat): LabView {
            val blurred = Mat()
            Imgproc.GaussianBlur(normalisedBgr, blurred, Size(0.0, 0.0), 1.0)
            val lab = Mat()
            Imgproc.cvtColor(blurred, lab, Imgproc.COLOR_BGR2Lab)
            val data = ByteArray(lab.rows() * lab.cols() * 3).also { lab.get(0, 0, it) }
            val v = LabView(lab.cols(), lab.rows(), data)
            blurred.release(); lab.release()
            return v
        }
    }
}

/** What changed between two photos: new holes and new ink, as masks in the flattened view. */
class Changes(
    /** 255 where paper went missing (thin slivers removed). */
    val holes: Mat,
    /** 255 where new marker ink appeared. */
    val ink: Mat,
    /** 255 where anything changed, before sorting (for the debug picture). */
    val changed: Mat,
) {
    fun release() { holes.release(); ink.release(); changed.release() }
}

/**
 * Diffs two aligned, light-evened photos of the same card (docs/SPEC.md,
 * Image processing step 4). A spot that changed is either:
 *  - **new ink**: bright and strongly coloured (paint marker), or
 *  - **a new hole**: anything else. What shows through a hole varies (purple
 *    board, white foam lighter than the paper, dirt), so a hole is found by
 *    change in any direction, not by being darker.
 * Thin slivers of change (ring lines a fraction of a mm off, edges of ink
 * strokes) are removed from the holes by their shape: a hole is round and at
 * least a few mm across.
 */
object ChangeDetector {
    /** Colour difference counted as a change, on the beige and in the black (8-bit Lab units). */
    const val CHANGE_BEIGE = 16.0
    const val CHANGE_BLACK = 22.0
    /** Paint-marker ink: at least this colourful and this light. */
    const val INK_CHROMA = 22.0
    const val INK_LIGHTNESS = 95

    /**
     * [usable]: 255 where both photos actually show the card (a corner can be
     * cut off by the edge of a photo).
     */
    fun detect(prev: LabView, cur: LabView, zones: CardZones, usable: Mat, smallestHoleMm: Double): Changes {
        val ok = ByteArray(cur.width * cur.height).also { usable.get(0, 0, it) }
        val n = cur.width * cur.height
        val changed = ByteArray(n); val holes = ByteArray(n); val ink = ByteArray(n)
        val zone = zones.zone
        for (i in 0 until n) {
            val z = zone[i].toInt()
            if (z == 0 || ok[i].toInt() == 0) continue
            val dl = (cur.l(i) - prev.l(i)).toDouble(); val da = (cur.a(i) - prev.a(i)).toDouble(); val db = (cur.b(i) - prev.b(i)).toDouble()
            val de = sqrt(dl * dl + da * da + db * db)
            if (de < (if (z == 2) CHANGE_BLACK else CHANGE_BEIGE)) continue
            changed[i] = -1
            if (isInk(cur, i)) ink[i] = -1 else holes[i] = -1
        }
        val w = cur.width; val h = cur.height
        val ppm = zones.box.pxPerMm
        val holeMat = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, holes) }
        // Remove slivers: anything a disc of ~45% of the smallest hole can't fit in.
        val k = (max(2.2, 0.45 * smallestHoleMm) * ppm).roundToInt() or 1
        Imgproc.morphologyEx(holeMat, holeMat, Imgproc.MORPH_OPEN, disc(k))
        Imgproc.morphologyEx(holeMat, holeMat, Imgproc.MORPH_CLOSE, disc((1.0 * ppm).roundToInt() or 1))
        Core.bitwise_and(holeMat, zones.paper, holeMat)
        val inkMat = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, ink) }
        Imgproc.morphologyEx(inkMat, inkMat, Imgproc.MORPH_OPEN, disc(3))
        Imgproc.morphologyEx(inkMat, inkMat, Imgproc.MORPH_CLOSE, disc((1.0 * ppm).roundToInt() or 1))
        Core.bitwise_and(inkMat, zones.paper, inkMat)
        val changedMat = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, changed) }
        return Changes(holeMat, inkMat, changedMat)
    }

    /** 255 where [view] shows the photo, 0 where the warp ran off it (pure black), shrunk by a few mm. */
    fun shownMask(view: Mat, ppm: Double): Mat {
        val off = Mat()
        Core.inRange(view, Scalar(0.0, 0.0, 0.0), Scalar(0.0, 0.0, 0.0), off)
        val shown = Mat(); Core.bitwise_not(off, shown); off.release()
        Imgproc.erode(shown, shown, disc((8 * ppm).roundToInt() or 1))
        return shown
    }

    fun isInk(v: LabView, i: Int) = v.chroma(i) > INK_CHROMA && v.l(i) > INK_LIGHTNESS

    fun disc(k: Int): Mat = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))
}
