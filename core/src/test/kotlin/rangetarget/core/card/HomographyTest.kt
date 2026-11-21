package rangetarget.core.card

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.opencv.core.Point
import rangetarget.core.SyntheticCard
import rangetarget.core.geometry.PointMm

class HomographyTest {
    private val h = SyntheticCard.camera()

    @Test
    fun `mm to px and back returns the same point`() {
        val p = PointMm(123.4, -56.7)
        val back = h.toMm(h.toPx(p))
        assertEquals(p.x, back.x, 1e-6)
        assertEquals(p.y, back.y, 1e-6)
    }

    @Test
    fun `composition applies the right-hand map first`() {
        val turn = Homography.rotationMm(90.0)
        val px = (h * turn).toPx(PointMm(10.0, 0.0))
        val expected = h.toPx(PointMm(0.0, 10.0))
        assertEquals(expected.x, px.x, 1e-6)
        assertEquals(expected.y, px.y, 1e-6)
    }

    @Test
    fun `top-down map puts the box corner at pixel zero with y down`() {
        val t = Homography.mmToTopDown(4.0, -100.0, 50.0)
        val (x, y) = t.map(-100.0, 50.0)
        assertEquals(0.0, x, 1e-9); assertEquals(0.0, y, 1e-9)
        val (x2, y2) = t.map(-99.0, 49.0)
        assertEquals(4.0, x2, 1e-9); assertEquals(4.0, y2, 1e-9)
    }

    @Test
    fun `Mat round trip keeps the matrix`() {
        rangetarget.core.OpenCvTestSupport.load()
        val back = Homography.fromMat(h.toMat())
        h.m.zip(back.m).forEach { (a, b) -> assertEquals(a, b, 1e-12) }
        assertEquals(Point(850.0, 800.0).x, h.toPx(PointMm.ORIGIN).x, 1e-9)
    }
}
