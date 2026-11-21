package rangetarget.core.holes

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.card.TopDown
import rangetarget.core.image.Plane
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Finds strongly coloured stickers (e.g. a fluorescent reactive splatter
 * target) stuck on the card. The card itself is only beige, black and pale
 * print, so anything vividly coloured and bigger than a pen mark is a sticker.
 * A sticker is treated as an unresolvable cluster: shots there are too dense to
 * separate (docs/SPEC.md, Edge cases: heavy overlap).
 *
 * Red printed aim-point stickers (Phase 3) will need telling apart from these;
 * for now every vivid region larger than [MIN_AREA_MM2] counts.
 */
object StickerFinder {
    const val MIN_AREA_MM2 = 600.0
    /** Distance from neutral grey in Lab (a, b) above which a pixel counts as vivid. */
    private const val VIVID = 28.0
    private const val MARGIN_MM = 4.0

    fun find(view: TopDown): List<UnresolvableCluster> {
        val lab = Mat().also { Imgproc.cvtColor(view.image, it, Imgproc.COLOR_BGR2Lab) }
        val parts = ArrayList<Mat>().also { Core.split(lab, it) }
        val a = Plane.of(parts[1]); val b = Plane.of(parts[2])
        val vivid = ByteArray(a.data.size) { i ->
            val da = (a.data[i].toInt() and 0xFF) - 128.0; val db = (b.data[i].toInt() and 0xFF) - 128.0
            if (hypot(da, db) > VIVID) -1 else 0
        }
        val mask = Plane(a.width, a.height, vivid).toMat()
        val k = (6 * view.pxPerMm).roundToInt() or 1
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        val pxArea = 1 / (view.pxPerMm * view.pxPerMm)
        // Count the vivid pixels actually inside each outline, not the area it
        // encloses: a closed pen loop encloses a lot but is itself thin.
        val vividPlane = Plane(a.width, a.height, vivid)
        fun vividArea(c: MatOfPoint): Double {
            val r = Imgproc.boundingRect(c)
            val poly = MatOfPoint2f(*c.toArray())
            var n = 0
            for (y in r.y until r.y + r.height) for (x in r.x until r.x + r.width) {
                if (vividPlane[x, y] != 0 && Imgproc.pointPolygonTest(poly, Point(x.toDouble(), y.toDouble()), false) >= 0) n++
            }
            return n * pxArea
        }
        val out = contours.filter { Imgproc.contourArea(it) * pxArea >= MIN_AREA_MM2 && vividArea(it) >= MIN_AREA_MM2 }.map { c ->
            val centre = Point(); val r = FloatArray(1)
            Imgproc.minEnclosingCircle(MatOfPoint2f(*c.toArray()), centre, r)
            UnresolvableCluster(view.toMm(centre.x, centre.y), r[0] / view.pxPerMm + MARGIN_MM, 0.0, 0,
                "Brightly coloured sticker, about %.0f mm across, shot out too heavily to separate holes".format(2 * r[0] / view.pxPerMm))
        }
        lab.release(); parts.forEach { it.release() }; mask.release()
        return out
    }
}
