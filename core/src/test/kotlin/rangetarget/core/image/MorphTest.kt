package rangetarget.core.image

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.OpenCvTestSupport
import kotlin.random.Random

/** The fast disc grow/shrink gives the same mask as OpenCV's, give or take a pixel at the rim. */
class MorphTest {
    companion object { @JvmStatic @BeforeAll fun setUp() = OpenCvTestSupport.load() }

    private fun mask(): Mat {
        val m = Mat.zeros(400, 500, CvType.CV_8UC1)
        val rnd = Random(7)
        repeat(40) { Imgproc.circle(m, Point(rnd.nextDouble(500.0), rnd.nextDouble(400.0)), rnd.nextInt(2, 25), Scalar(255.0), if (rnd.nextBoolean()) -1 else 2) }
        Imgproc.rectangle(m, Point(0.0, 0.0), Point(60.0, 399.0), Scalar(255.0), -1)
        return m
    }

    /** Pixels that differ, and how many of them are more than a pixel from the slow result's rim. */
    private fun compare(a: Mat, b: Mat, k: Int): Pair<Int, Int> {
        val diff = Mat(); Core.absdiff(a, b, diff)
        val n = Core.countNonZero(diff)
        val rim = Mat(); val g = Mat(); val e = Mat()
        Imgproc.dilate(b, g, Morph.disc(3)); Imgproc.erode(b, e, Morph.disc(3)); Core.absdiff(g, e, rim)
        val notRim = Mat(); Core.bitwise_not(rim, notRim); Core.bitwise_and(diff, notRim, diff)
        return n to Core.countNonZero(diff)
    }

    @Test fun matchesOpenCv() {
        val m = mask()
        for (k in listOf(15, 31, 49, 181)) {
            val fast = Mat(); val slow = Mat()
            Morph.dilateDisc(m, fast, k); Imgproc.dilate(m, slow, Morph.disc(k))
            val (n1, off1) = compare(fast, slow, k)
            Morph.erodeDisc(m, fast, k); Imgproc.erode(m, slow, Morph.disc(k))
            val (n2, off2) = compare(fast, slow, k)
            assertTrue(off1 == 0 && off2 == 0, "k=$k: dilate $n1 differ ($off1 off the rim), erode $n2 ($off2)")
            assertTrue(n1 < 0.01 * m.total() && n2 < 0.01 * m.total(), "k=$k: dilate $n1, erode $n2 pixels differ")
        }
    }
}
