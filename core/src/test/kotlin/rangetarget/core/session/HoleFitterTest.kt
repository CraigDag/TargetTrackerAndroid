package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.geometry.PointMm
import kotlin.math.hypot

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HoleFitterTest {
    private val box = ViewBox(4.0, -50.0, 50.0, -50.0, 50.0)
    private val d = 10.0

    @BeforeAll fun load() = OpenCvTestSupport.load()

    private fun blank() = Mat.zeros(box.heightPx, box.widthPx, CvType.CV_8UC1)
    private fun draw(m: Mat, vararg c: PointMm) { for (p in c) Imgproc.circle(m, box.toPx(p), (d / 2 * box.pxPerMm).toInt(), Scalar(255.0), -1) }
    private fun near(found: List<FoundHole>, p: PointMm, tol: Double) = found.any { hypot(it.centreMm.x - p.x, it.centreMm.y - p.y) <= tol }

    @Test
    fun `separate clean holes give their centres`() {
        val holes = blank(); draw(holes, PointMm(-20.0, 10.0), PointMm(25.0, -15.0))
        val found = HoleFitter(holes, blank(), blank(), box, d).fitAll()
        assertEquals(2, found.size)
        assertTrue(found.all { it.source == ShotSource.CLEAN })
        assertTrue(near(found, PointMm(-20.0, 10.0), 0.5) && near(found, PointMm(25.0, -15.0), 0.5), "$found")
    }

    @Test
    fun `two overlapping holes are split into two`() {
        val a = PointMm(0.0, 0.0); val b = PointMm(7.0, 0.0)
        val holes = blank(); draw(holes, a, b)
        val found = HoleFitter(holes, blank(), blank(), box, d).fitAll()
        assertEquals(2, found.size, "$found")
        assertTrue(found.all { it.source == ShotSource.MERGED })
        assertTrue(near(found, a, 1.5) && near(found, b, 1.5), "$found")
    }

    @Test
    fun `a crescent beside an old hole becomes one hole overlapping it`() {
        val old = blank(); draw(old, PointMm(0.0, 0.0))
        val both = blank(); draw(both, PointMm(0.0, 0.0), PointMm(5.0, 0.0))
        val crescent = Mat(); org.opencv.core.Core.subtract(both, old, crescent)
        val found = HoleFitter(crescent, old, blank(), box, d).fitAll()
        assertEquals(1, found.size, "$found")
        assertEquals(ShotSource.GROWN_HOLE, found[0].source)
        assertTrue(near(found, PointMm(5.0, 0.0), 1.5), "$found")
    }

    @Test
    fun `a short group can ask for one more hole in the biggest blob`() {
        val holes = blank(); draw(holes, PointMm(0.0, 0.0), PointMm(4.0, 0.0))  // heavy overlap
        val fitter = HoleFitter(holes, blank(), blank(), box, d)
        val first = fitter.fitAll().size
        val (blob, _) = fitter.splitCandidates().first()
        assertTrue(fitter.addHole(blob))
        assertEquals(first + 1, fitter.holes().size)
    }
}
