package rangetarget.core.card

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.geometry.PointMm
import kotlin.math.roundToInt

/**
 * A flattened, top-down view of the card in its mm frame: [pxPerMm] pixels per
 * millimetre, covering card x from [left] to [right] and y from [bottom] to [top].
 */
class TopDown(val image: Mat, val pxPerMm: Double, val left: Double, val top: Double) {
    val toImage: Homography = Homography.mmToTopDown(pxPerMm, left, top)

    fun toPx(p: PointMm): Point = Point((p.x - left) * pxPerMm, (top - p.y) * pxPerMm)
    fun toMm(x: Double, y: Double): PointMm = PointMm(left + x / pxPerMm, top - y / pxPerMm)

    companion object {
        /** Warps [photo] through [cardToPhoto] into a top-down view of the given mm box. */
        fun warp(photo: Mat, cardToPhoto: CardToPhoto, pxPerMm: Double,
                 left: Double, right: Double, bottom: Double, top: Double,
                 interpolation: Int = Imgproc.INTER_LINEAR): TopDown {
            val toImage = Homography.mmToTopDown(pxPerMm, left, top)
            val w = ((right - left) * pxPerMm).roundToInt(); val h = ((top - bottom) * pxPerMm).roundToInt()
            val out = Mat()
            val plain = (cardToPhoto as? CardMap)?.takeIf { it.bendPx.isEmpty() }?.homography ?: cardToPhoto as? Homography
            if (plain != null) {
                // Map from top-down pixels back to photo pixels.
                val m = (plain * toImage.inverse()).toMat()
                Imgproc.warpPerspective(photo, out, m, Size(w.toDouble(), h.toDouble()), interpolation or Imgproc.WARP_INVERSE_MAP,
                    org.opencv.core.Core.BORDER_CONSTANT, org.opencv.core.Scalar(0.0, 0.0, 0.0))
                m.release()
            } else {
                // A bent map: look up the photo pixel for every top-down pixel.
                val mx = FloatArray(w * h); val my = FloatArray(w * h)
                for (y in 0 until h) for (x in 0 until w) {
                    val (u, v) = cardToPhoto.map(left + x / pxPerMm, top - y / pxPerMm)
                    mx[y * w + x] = u.toFloat(); my[y * w + x] = v.toFloat()
                }
                val mapX = Mat(h, w, org.opencv.core.CvType.CV_32FC1).also { it.put(0, 0, mx) }
                val mapY = Mat(h, w, org.opencv.core.CvType.CV_32FC1).also { it.put(0, 0, my) }
                Imgproc.remap(photo, out, mapX, mapY, interpolation, org.opencv.core.Core.BORDER_CONSTANT, org.opencv.core.Scalar(0.0, 0.0, 0.0))
                mapX.release(); mapY.release()
            }
            return TopDown(out, pxPerMm, left, top)
        }
    }
}
