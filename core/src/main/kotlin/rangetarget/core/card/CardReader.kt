package rangetarget.core.card

import kotlinx.serialization.Serializable
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.RangeTarget
import rangetarget.core.debug.DebugSink
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import rangetarget.core.holes.Hole
import rangetarget.core.holes.HoleDetector
import rangetarget.core.holes.StickerFinder
import rangetarget.core.holes.UnresolvableCluster
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Everything read from one photo of a card. Written to card.json by the desktop tool. */
@Serializable
data class CardReading(
    val coreVersion: String,
    val photoWidthPx: Int,
    val photoHeightPx: Int,
    val card: CardFit,
    val flatPxPerMm: Double,
    val typicalHoleAreaMm2: Double,
    val holes: List<Hole>,
    val clusters: List<UnresolvableCluster>,
    val flags: List<Flag>,
) {
    val confidentHoles get() = holes.count { it.confident }
}

/**
 * Phase 1 pipeline for a single photo: find the card, flatten it, find holes.
 * Each step hands an annotated image to [DebugSink].
 */
object CardReader {
    const val FLAT_PX_PER_MM = 4.0
    /** The flattened view covers the paper plus a little margin. */
    private const val HALF_WIDTH_MM = 290.0
    private const val HALF_HEIGHT_MM = 275.0

    fun read(photo: Mat, debug: DebugSink = DebugSink.NONE): CardReading? {
        val found = CardFinder.find(photo, debug) ?: return null
        val fit = found.fit
        found.edges.view.image.release(); found.edges.paperMask.release()

        val view = TopDown.warp(photo, fit.cardToPhoto, FLAT_PX_PER_MM,
            -HALF_WIDTH_MM, HALF_WIDTH_MM, -HALF_HEIGHT_MM, HALF_HEIGHT_MM)
        debug.save("05_flat", view.image.clone())
        debug.save("06_flat_with_rings", drawFlatRings(view, fit))

        val outline = fit.paperOutlineMm ?: nominalPaper()
        val stickers = StickerFinder.find(view)
        val det = HoleDetector.detect(view, outline, stickers)
        debug.save("07_hole_pixels", drawMask(view, det.rawMask, det.holeMask))
        val flags = fit.flags.toMutableList()
        det.clusters.forEach { flags += Flag(FlagCode.UNRESOLVABLE_CLUSTER, it.reason, it.centreMm) }
        val reading = CardReading(RangeTarget.VERSION, photo.cols(), photo.rows(), fit, FLAT_PX_PER_MM,
            det.typicalHoleAreaMm2, det.holes, det.clusters, flags)
        debug.save("08_holes", drawHoles(view, reading))
        debug.save("09_holes_on_photo", drawHolesOnPhoto(photo, reading))
        det.rawMask.release(); det.holeMask.release(); view.image.release()
        return reading
    }

    private fun nominalPaper(): List<PointMm> {
        val x = CardGeometry.PAPER_WIDTH_MM / 2; val y = CardGeometry.PAPER_HEIGHT_MM / 2
        return listOf(PointMm(-x, y), PointMm(x, y), PointMm(x, -y), PointMm(-x, -y))
    }

    private fun thickness(img: Mat) = max(1, (img.cols() / 900.0).roundToInt())

    private fun drawFlatRings(view: TopDown, fit: CardFit): Mat {
        val out = view.image.clone()
        RingFitter.drawRings(out, view.toImage, YELLOW, 1)
        fit.paperOutlineMm?.let { c ->
            Imgproc.polylines(out, listOf(MatOfPoint(*c.map { view.toPx(it) }.toTypedArray())), true, CYAN, 2, Imgproc.LINE_AA)
        }
        return captioned(out, "Step 4: flattened, top-down, 4 px per mm. Yellow = ideal ISSF rings, cyan = paper edge found", "Rings miss the print by %.1f mm typically, %.1f mm at worst (95%%)".format(fit.ringMissMedianMm, fit.ringMissP95Mm))
    }

    private fun drawMask(view: TopDown, raw: Mat, kept: Mat): Mat {
        val out = view.image.clone()
        out.setTo(MAGENTA, raw)
        out.setTo(CYAN, kept)
        return captioned(out, "Step 5: 'not paper' pixels. Magenta = thin things removed (ring lines, pen, numbers, creases)", "Cyan = what is left: hole candidates")
    }

    private fun drawHoles(view: TopDown, r: CardReading): Mat {
        val out = view.image.clone()
        val t = thickness(out)
        RingFitter.drawRings(out, view.toImage, Scalar(150.0, 150.0, 150.0), 1)
        for (c in r.clusters) {
            Imgproc.circle(out, view.toPx(c.centreMm), (c.radiusMm * view.pxPerMm).roundToInt(), MAGENTA, 2 * t, Imgproc.LINE_AA)
            // Label to the left of the circle, clear of the holes around it.
            val at = view.toPx(PointMm(c.centreMm.x - c.radiusMm - 75, c.centreMm.y + c.radiusMm * 0.7))
            for ((i, text) in listOf("sticker cluster", "at least ${c.roughShotsAtLeast} shots").withIndex()) {
                val p = Point(at.x, at.y + i * 34)
                Imgproc.putText(out, text, p, Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, Scalar(255.0, 255.0, 255.0), 6, Imgproc.LINE_AA)
                Imgproc.putText(out, text, p, Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, MAGENTA, 2, Imgproc.LINE_AA)
            }
        }
        for (h in r.holes) {
            val colour = holeColour(h)
            val p = view.toPx(h.centreMm)
            val rad = if (h.shotsInBlob > 1) 4.5 * view.pxPerMm else h.blobDiameterMm / 2 * view.pxPerMm + 3
            Imgproc.circle(out, p, rad.roundToInt(), colour, t, Imgproc.LINE_AA)
            Imgproc.drawMarker(out, p, colour, Imgproc.MARKER_CROSS, 8, 1)
            Imgproc.putText(out, h.id.toString(), Point(p.x + rad * 0.75, p.y - rad * 0.75), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, colour, 2, Imgproc.LINE_AA)
        }
        val single = r.holes.count { it.confident }
        val overl = r.holes.count { it.shotsInBlob > 1 }
        val unsure = r.holes.count { !it.confident && it.shotsInBlob == 1 }
        return captioned(out, "Step 6: holes. Green = clear single hole ($single), orange = part of merged holes ($overl), red = unsure ($unsure)", "Magenta = sticker area, holes not separated. Total holes listed: ${r.holes.size}")
    }

    private fun drawHolesOnPhoto(photo: Mat, r: CardReading): Mat {
        val out = photo.clone()
        val h = r.card.cardToPhoto
        val t = thickness(out)
        RingFitter.drawRings(out, h, Scalar(150.0, 150.0, 150.0), 1)
        for (c in r.clusters) {
            val pts = (0..72).map { k -> h.toPx(PointMm(c.centreMm.x + c.radiusMm * cos(k * PI / 36), c.centreMm.y + c.radiusMm * sin(k * PI / 36))) }
            Imgproc.polylines(out, listOf(MatOfPoint(*pts.toTypedArray())), true, MAGENTA, 2 * t, Imgproc.LINE_AA)
        }
        for (hole in r.holes) {
            val p = h.toPx(hole.centreMm)
            val rad = (if (hole.shotsInBlob > 1) 4.5 else hole.blobDiameterMm / 2 + 1) * h.pxPerMmAt(hole.centreMm)
            Imgproc.circle(out, p, rad.roundToInt(), holeColour(hole), t, Imgproc.LINE_AA)
        }
        return captioned(out, "Step 7: the same holes drawn back on the original photo")
    }

    private fun holeColour(h: Hole) = when {
        h.confident -> GREEN
        h.shotsInBlob > 1 -> Scalar(0.0, 140.0, 255.0)
        else -> RED
    }

}
