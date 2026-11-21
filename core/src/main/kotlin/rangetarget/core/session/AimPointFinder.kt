package rangetarget.core.session

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.PointMm
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Finds the aim points in the baseline photo (docs/SPEC.md, Aim points): red
 * printed stickers or hand-drawn filled marker circles, each with a number.
 * Position comes from the coloured disc, which is accurate to well under a
 * millimetre. The number is read as a guess, limited to the numbers in use,
 * with a confidence, for the shooter to confirm.
 */
object AimPointFinder {
    private const val MIN_DIAMETER_MM = 18.0
    private const val MAX_DIAMETER_MM = 80.0

    fun find(normalisedBgr: Mat, lab: LabView, zones: CardZones, highestNumber: Int? = null): List<AimPointReading> {
        val box = zones.box
        val ppm = box.pxPerMm
        val w = lab.width; val h = lab.height
        val vivid = ByteArray(w * h) { i -> if (zones.zone[i].toInt() == 1 && lab.chroma(i) > 32 && lab.l(i) > 60) -1 else 0 }
        val mask = Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, vivid) }
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, ChangeDetector.disc((1.5 * ppm).roundToInt() or 1))
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, ChangeDetector.disc((2.0 * ppm).roundToInt() or 1))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
        mask.release()
        val bgr = ByteArray(w * h * 3).also { normalisedBgr.get(0, 0, it) }

        class Disc(val centre: Point, val radiusPx: Double, val rgb: String, val digits: Mat?)
        val discs = contours.mapNotNull { c ->
            val area = Imgproc.contourArea(c)
            val d = 2 * sqrt(area / PI) / ppm
            if (d !in MIN_DIAMETER_MM..MAX_DIAMETER_MM) return@mapNotNull null
            val perim = Imgproc.arcLength(MatOfPoint2f(*c.toArray()), true)
            if (4 * PI * area / (perim * perim) < 0.6) return@mapNotNull null
            val m = Imgproc.moments(c)
            val centre = Point(m.m10 / m.m00, m.m01 / m.m00)
            val rPx = d / 2 * ppm
            // Colour from the disc's vivid pixels; the number from its dark ones.
            var sb = 0.0; var sg = 0.0; var sr = 0.0; var n = 0
            val lightness = ArrayList<Int>()
            val x0 = (centre.x - rPx).toInt().coerceAtLeast(0); val x1 = (centre.x + rPx).toInt().coerceAtMost(w - 1)
            val y0 = (centre.y - rPx).toInt().coerceAtLeast(0); val y1 = (centre.y + rPx).toInt().coerceAtMost(h - 1)
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * w + x
                val dx = x - centre.x; val dy = y - centre.y
                if (dx * dx + dy * dy > rPx * rPx * 0.8) continue
                if (vivid[i].toInt() != 0) {
                    sb += bgr[3 * i].toInt() and 0xFF; sg += bgr[3 * i + 1].toInt() and 0xFF; sr += bgr[3 * i + 2].toInt() and 0xFF; n++
                    lightness += lab.l(i)
                }
            }
            if (n == 0) return@mapNotNull null
            val discL = lightness.sorted()[lightness.size / 2]
            val digits = Mat.zeros(y1 - y0 + 1, x1 - x0 + 1, CvType.CV_8UC1)
            for (y in y0..y1) for (x in x0..x1) {
                val i = y * w + x
                val dx = x - centre.x; val dy = y - centre.y
                if (dx * dx + dy * dy > rPx * rPx * 0.72) continue
                if (lab.l(i) < 0.6 * discL && lab.chroma(i) < 28) digits.put(y - y0, x - x0, 255.0)
            }
            Imgproc.morphologyEx(digits, digits, Imgproc.MORPH_OPEN, ChangeDetector.disc(3))
            Disc(centre, rPx, MarkerColours.hex(sr / n, sg / n, sb / n), if (Core.countNonZero(digits) > 0.3 * ppm * ppm * 20) digits else { digits.release(); null })
        }.sortedWith(compareByDescending<Disc> { box.toMm(it.centre.x, it.centre.y).y.roundToInt() / 40 }.thenBy { it.centre.x })

        // Read numbers: each disc against each candidate number, then hand out
        // numbers best match first so no number is used twice.
        val top = max(highestNumber ?: 0, discs.size).coerceIn(1, 20)
        val scores = discs.map { d -> d.digits?.let { m -> (1..top).associateWith { DigitReader.score(m, it) } } ?: emptyMap() }
        discs.forEach { it.digits?.release() }
        val chosen = arrayOfNulls<Int>(discs.size)
        val pairs = scores.flatMapIndexed { i, s -> s.map { (num, v) -> Triple(i, num, v) } }.sortedByDescending { it.third }
        val taken = HashSet<Int>()
        for ((i, num, v) in pairs) if (chosen[i] == null && num !in taken && v > 0.25) { chosen[i] = num; taken += num }

        return discs.mapIndexed { i, d ->
            val ranked = scores[i].entries.sortedByDescending { it.value }
            val pick = chosen[i]
            val best = ranked.firstOrNull()?.value ?: 0.0
            val pickScore = pick?.let { scores[i][it] } ?: 0.0
            val second = ranked.firstOrNull { it.key != pick }?.value ?: 0.0
            val conf = if (pick == null) 0.0 else (((pickScore - second) / 0.15).coerceIn(0.0, 1.0) * ((pickScore - 0.3) / 0.4).coerceIn(0.0, 1.0))
            val name = MarkerColours.nameOf(d.rgb)
            val style = if (name == "red") AimPointStyle.STICKER else AimPointStyle.HAND_DRAWN
            val centreMm = box.toMm(d.centre.x, d.centre.y)
            val flags = ArrayList<Flag>()
            if (pick == null) flags += Flag(FlagCode.AIM_POINT_NUMBER_UNSURE, "No number could be read on this aim point", centreMm)
            else if (conf < 0.35 || pickScore < best - 1e-9) flags += Flag(FlagCode.AIM_POINT_NUMBER_UNSURE,
                "Read as $pick, but ${ranked.filter { it.key != pick }.take(2).joinToString(" or ") { it.key.toString() }} also look possible", centreMm)
            AimPointReading(i + 1, centreMm, 2 * d.radiusPx / ppm, style, d.rgb, name, pick, conf,
                ranked.map { it.key }.filter { it != pick }.take(3), flags)
        }
    }
}

/**
 * Reads a printed or handwritten number by comparing its shape with numbers
 * drawn in a few typefaces, turned a little either way. Good for 1–20, which
 * is all aim points need.
 */
object DigitReader {
    private const val SIDE = 40
    private val FONTS = intArrayOf(Imgproc.FONT_HERSHEY_DUPLEX, Imgproc.FONT_HERSHEY_SIMPLEX, Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX, Imgproc.FONT_HERSHEY_TRIPLEX)
    private val ANGLES = doubleArrayOf(-16.0, -8.0, 0.0, 8.0, 16.0)
    private val cache = HashMap<Int, List<FloatArray>>()

    /** Best similarity (−1..1) between the dark-pixel mask [ink] and the number [n]. */
    fun score(ink: Mat, n: Int): Double {
        val obs = normalised(ink) ?: return 0.0
        val tpls = synchronized(cache) { cache.getOrPut(n) { templates(n) } }
        return tpls.maxOf { ncc(obs, it) }
    }

    private fun templates(n: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (f in FONTS) for (thick in intArrayOf(3, 6)) for (a in ANGLES) {
            val canvas = Mat.zeros(160, 200, CvType.CV_8UC1)
            val text = n.toString()
            val base = IntArray(1)
            val size = Imgproc.getTextSize(text, f, 2.5, thick, base)
            Imgproc.putText(canvas, text, Point((200 - size.width) / 2, (160 + size.height) / 2), f, 2.5, Scalar(255.0), thick, Imgproc.LINE_AA)
            val rot = Imgproc.getRotationMatrix2D(Point(100.0, 80.0), a, 1.0)
            val turned = Mat()
            Imgproc.warpAffine(canvas, turned, rot, canvas.size())
            normalised(turned)?.let { out += it }
            canvas.release(); turned.release(); rot.release()
        }
        return out
    }

    /** Crops to the marks, pads to a square around them and scales to SIDE×SIDE, softened a little. */
    private fun normalised(m: Mat): FloatArray? {
        val pts = Mat()
        Core.findNonZero(m, pts)
        if (pts.rows() < 5) { pts.release(); return null }
        val r = Imgproc.boundingRect(pts)
        pts.release()
        val side = (max(r.width, r.height) * 1.15).roundToInt() + 2
        val sq = Mat.zeros(side, side, CvType.CV_8UC1)
        val ox = (side - r.width) / 2; val oy = (side - r.height) / 2
        m.submat(r).copyTo(sq.submat(Rect(ox, oy, r.width, r.height)))
        val small = Mat()
        Imgproc.resize(sq, small, Size(SIDE.toDouble(), SIDE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), 1.2)
        val f = Mat(); small.convertTo(f, CvType.CV_32F)
        val arr = FloatArray(SIDE * SIDE).also { f.get(0, 0, it) }
        sq.release(); small.release(); f.release()
        return arr
    }

    private fun ncc(a: FloatArray, b: FloatArray): Double {
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in a.indices) { val x = a[i] - ma; val y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        return if (saa <= 0 || sbb <= 0) 0.0 else sab / sqrt(saa * sbb)
    }

}
