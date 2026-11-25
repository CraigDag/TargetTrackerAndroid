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
        /** Spacing in pixels of the grid on which a bent map is worked out exactly. */
        const val GRID_PX = 8

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
                // The map is smooth, so it is worked out exactly on a grid every GRID_PX pixels
                // and filled in between by straight-line interpolation (off by far less than a
                // hundredth of a pixel). Working it out for every pixel was a large share of the
                // time taken to read a photo.
                val gw = (w - 1) / GRID_PX + 2; val gh = (h - 1) / GRID_PX + 2
                val gx = DoubleArray(gw * gh); val gy = DoubleArray(gw * gh)
                for (j in 0 until gh) for (i in 0 until gw) {
                    val (u, v) = cardToPhoto.map(left + i * GRID_PX / pxPerMm, top - j * GRID_PX / pxPerMm)
                    gx[j * gw + i] = u; gy[j * gw + i] = v
                }
                val mx = FloatArray(w * h); val my = FloatArray(w * h)
                for (y in 0 until h) {
                    val j = y / GRID_PX; val fy = (y - j * GRID_PX).toDouble() / GRID_PX
                    for (x in 0 until w) {
                        val i = x / GRID_PX; val fx = (x - i * GRID_PX).toDouble() / GRID_PX
                        val a = j * gw + i; val b = a + gw
                        val w00 = (1 - fx) * (1 - fy); val w10 = fx * (1 - fy); val w01 = (1 - fx) * fy; val w11 = fx * fy
                        mx[y * w + x] = (gx[a] * w00 + gx[a + 1] * w10 + gx[b] * w01 + gx[b + 1] * w11).toFloat()
                        my[y * w + x] = (gy[a] * w00 + gy[a + 1] * w10 + gy[b] * w01 + gy[b + 1] * w11).toFloat()
                    }
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
