package rangetarget.core.image

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Growing and shrinking masks by a round disc. OpenCV's dilate/erode cost
 * grows with the disc size, and some steps grow masks by several centimetres
 * (a disc a couple of hundred pixels wide), which made them the slowest part of
 * reading a photo. Above [FAST_MIN_PX] these use a distance transform instead,
 * whose cost doesn't depend on the disc size; the result matches OpenCV's
 * elliptical disc to within a pixel at the rim.
 */
object Morph {
    /** Discs narrower than this go through OpenCV's dilate/erode, which is fast for them. */
    const val FAST_MIN_PX = 15

    /** The same disc as `getStructuringElement(MORPH_ELLIPSE, k × k)`. */
    fun disc(k: Int): Mat = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble()))

    /** OpenCV's elliptical k × k disc reaches round(sqrt(r² − dy²)) with r = k / 2, so a Euclidean reach of r + 0.5. */
    private fun reach(k: Int) = (k / 2) + 0.5

    /** [dst] = [src] (8-bit mask) grown by a disc [k] pixels across. [dst] may be [src]. */
    fun dilateDisc(src: Mat, dst: Mat, k: Int) {
        if (k < FAST_MIN_PX) { val se = disc(k); Imgproc.dilate(src, dst, se); se.release(); return }
        val empty = Mat(); Imgproc.threshold(src, empty, 0.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val dist = Mat(); Imgproc.distanceTransform(empty, dist, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
        empty.release()
        Imgproc.threshold(dist, dist, reach(k), 255.0, Imgproc.THRESH_BINARY_INV)
        dist.convertTo(dst, CvType.CV_8U); dist.release()
    }

    /** [dst] = [src] (8-bit mask) shrunk by a disc [k] pixels across. [dst] may be [src]. */
    fun erodeDisc(src: Mat, dst: Mat, k: Int) {
        if (k < FAST_MIN_PX) { val se = disc(k); Imgproc.erode(src, dst, se); se.release(); return }
        val dist = Mat(); Imgproc.distanceTransform(src, dist, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE)
        Imgproc.threshold(dist, dist, reach(k), 255.0, Imgproc.THRESH_BINARY)
        dist.convertTo(dst, CvType.CV_8U); dist.release()
    }

    /** Morphological closing (grow, then shrink) by a disc [k] pixels across. */
    fun closeDisc(src: Mat, dst: Mat, k: Int) {
        if (k < FAST_MIN_PX) { val se = disc(k); Imgproc.morphologyEx(src, dst, Imgproc.MORPH_CLOSE, se); se.release(); return }
        dilateDisc(src, dst, k); erodeDisc(dst, dst, k)
    }

    /**
     * How far each pixel is from [mask], worked out once so the mask can be grown
     * by several disc sizes. Same result as [dilateDisc]. Call [release] when done.
     */
    class Reach(mask: Mat) {
        private val src = mask
        private var dist: Mat? = null

        /** A new caller-owned mask: [mask] grown by a disc [k] pixels across. */
        fun within(k: Int): Mat {
            val out = Mat()
            if (k < FAST_MIN_PX) { dilateDisc(src, out, k); return out }
            val d = dist ?: Mat().also { m ->
                val empty = Mat(); Imgproc.threshold(src, empty, 0.0, 255.0, Imgproc.THRESH_BINARY_INV)
                Imgproc.distanceTransform(empty, m, Imgproc.DIST_L2, Imgproc.DIST_MASK_PRECISE); empty.release()
                dist = m
            }
            val t = Mat(); Imgproc.threshold(d, t, reach(k), 255.0, Imgproc.THRESH_BINARY_INV)
            t.convertTo(out, CvType.CV_8U); t.release()
            return out
        }

        fun release() { dist?.release(); dist = null }
    }
}
