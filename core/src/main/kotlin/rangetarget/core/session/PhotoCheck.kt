package rangetarget.core.session

import kotlinx.serialization.Serializable
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import rangetarget.core.card.CardToPhoto
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How good a photo is for finding holes, measured once the card has been found. */
@Serializable
data class PhotoQuality(
    /** How far the black disc's edge is smeared in the photo: 25% to 75% of the way from black to paper, mm. A sharp photo is about 0.3 mm. */
    val edgeBlurMm: Double,
    /** Fewest photo pixels per mm anywhere on the card (the far side of a tilted photo). */
    val leastPxPerMm: Double,
    /** Most pixels per mm anywhere on the card divided by the fewest: 1.0 is square on. */
    val tiltRatio: Double,
    /** Darkest patch of paper divided by the brightest (typical of the darkest and brightest tenth): 1.0 is even light. */
    val evenLight: Double,
    /** Share of the card that is burnt out white by glare, 0–1. */
    val glare: Double,
)

/**
 * A quick check of each photo, so the shooter can retake a poor one at the
 * range rather than find out later (docs/SPEC.md, Photo check). Each problem
 * becomes a flag with plain advice; nothing is changed in the analysis.
 */
object PhotoCheck {
    // Limits chosen from the synthetic validation set (docs/photo-check.md): the "careful
    // photo" preset passes, the "hurried photo" preset mostly does not.
    const val BLUR_LIMIT_MM = 0.75
    const val LEAST_PX_PER_MM = 2.6
    const val TILT_LIMIT = 1.45
    const val EVEN_LIGHT_LIMIT = 0.62
    const val GLARE_LIMIT = 0.02

    fun measure(photo: Mat, cardToPhoto: CardToPhoto, view: Mat, zones: CardZones): PhotoQuality {
        val gray = Mat(); Imgproc.cvtColor(photo, gray, Imgproc.COLOR_BGR2GRAY)
        val w = gray.cols(); val h = gray.rows()
        val px = ByteArray(w * h).also { gray.get(0, 0, it) }
        gray.release()
        fun at(x: Double, y: Double): Double {
            if (x < 0 || y < 0 || x >= w - 1 || y >= h - 1) return Double.NaN
            val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val fx = x - x0; val fy = y - y0
            val i = y0 * w + x0
            fun v(k: Int) = (px[k].toInt() and 0xFF).toDouble()
            return v(i) * (1 - fx) * (1 - fy) + v(i + 1) * fx * (1 - fy) + v(i + w) * (1 - fx) * fy + v(i + w + 1) * fx * fy
        }
        val blur = edgeBlurMm(cardToPhoto, ::at)

        // Pixels per mm over the card: the far side of a tilted photo has the fewest.
        val scales = ArrayList<Double>()
        for (x in -250..250 step 50) for (y in -250..250 step 50) scales += cardToPhoto.pxPerMmAt(PointMm(x.toDouble(), y.toDouble()))
        val least = scales.min(); val most = scales.max()

        // Light: the paper's brightness in 40 mm patches, in the flattened view.
        val vg = Mat(); Imgproc.cvtColor(view, vg, Imgproc.COLOR_BGR2GRAY)
        val vw = vg.cols(); val vh = vg.rows()
        val v = ByteArray(vw * vh).also { vg.get(0, 0, it) }
        vg.release()
        val box = zones.box
        val cell = (40 * box.pxPerMm).toInt()
        val patches = ArrayList<Double>()
        var paper = 0; var burnt = 0
        for (cy in 0 until vh / cell) for (cx in 0 until vw / cell) {
            val vals = ArrayList<Int>()
            for (y in cy * cell until (cy + 1) * cell step 2) for (x in cx * cell until (cx + 1) * cell step 2) {
                val i = y * vw + x
                val z = zones.zone[i].toInt()
                if (z == 0) continue
                val g = v[i].toInt() and 0xFF
                paper++; if (g >= 250) burnt++
                if (z == 1) vals += g
            }
            if (vals.size > cell * cell / 8) { vals.sort(); patches += vals[vals.size * 3 / 4].toDouble() }
        }
        val even = if (patches.size >= 6) {
            patches.sort()
            patches[patches.size / 10] / max(1.0, patches[patches.size - 1 - patches.size / 10])
        } else 1.0
        return PhotoQuality(blur, least, most / max(1e-9, least), even, if (paper > 0) burnt.toDouble() / paper else 0.0)
    }

    /**
     * Median over many directions of the distance (in card mm) over which the photo
     * goes from 25% to 75% of the way from the black disc to the paper around it.
     * Directions crossed by a hole, ink or a sticker give odd profiles and are skipped.
     */
    internal fun edgeBlurMm(map: CardToPhoto, at: (Double, Double) -> Double): Double {
        val r0 = CardGeometry.blackRadiusMm
        val step = 0.1
        val n = (16.0 / step).toInt() + 1
        val widths = ArrayList<Double>()
        for (k in 0 until 180) {
            val a = 2 * PI * k / 180
            val prof = DoubleArray(n) { i ->
                val r = r0 - 8.0 + i * step
                val (u, vv) = map.map(r * cos(a), r * sin(a))
                at(u, vv)
            }
            if (prof.any { it.isNaN() }) continue
            val inner = prof.copyOfRange(0, 30).sorted()[15]
            val outer = prof.copyOfRange(n - 30, n).sorted()[15]
            if (outer - inner < 25) continue
            val lo = inner + 0.25 * (outer - inner); val hi = inner + 0.75 * (outer - inner)
            // The first crossing of each level from the inside, interpolated.
            fun crossing(level: Double): Double? {
                for (i in 1 until n) if (prof[i - 1] < level && prof[i] >= level) return (i - 1 + (level - prof[i - 1]) / (prof[i] - prof[i - 1])) * step
                return null
            }
            val a25 = crossing(lo) ?: continue; val a75 = crossing(hi) ?: continue
            // A clean edge has a single rise: nothing dips back below the 25% level after it.
            val back = (min(n - 1, ((a75 / step).toInt() + 1)) until n).any { prof[it] < lo }
            if (back || a75 < a25) continue
            widths += a75 - a25
        }
        if (widths.size < 20) return Double.NaN
        widths.sort()
        return widths[widths.size / 2]
    }

    /** Plain advice for each problem found; empty for a good photo. */
    fun flags(q: PhotoQuality): List<Flag> {
        val out = ArrayList<Flag>()
        if (!q.edgeBlurMm.isNaN() && q.edgeBlurMm > BLUR_LIMIT_MM) out += Flag(FlagCode.PHOTO_BLURRY,
            "The photo looks blurry, so small or touching holes may be missed. Hold the phone still (or brace it) and tap the card on the screen to focus before taking it.")
        if (q.leastPxPerMm < LEAST_PX_PER_MM) out += Flag(FlagCode.PHOTO_TOO_FAR,
            "The card is small in the photo, so holes are only a few pixels across. Step closer so the card fills most of the frame.")
        if (q.tiltRatio > TILT_LIMIT) out += Flag(FlagCode.PHOTO_TILTED,
            "The photo was taken at an angle, so the far side of the card is squashed. Hold the phone square on to the card, in the middle of it.")
        if (q.evenLight < EVEN_LIGHT_LIMIT) out += Flag(FlagCode.PHOTO_UNEVEN_LIGHT,
            "Part of the card is in shadow. Stand so your body or the phone doesn't shade the card, or move the card into even light.")
        if (q.glare > GLARE_LIMIT) out += Flag(FlagCode.PHOTO_GLARE,
            "There's glare on the card, which can hide holes. Tilt the phone slightly or turn off the flash.")
        return out
    }
}
