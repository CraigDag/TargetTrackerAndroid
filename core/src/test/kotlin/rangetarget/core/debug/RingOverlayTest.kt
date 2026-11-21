package rangetarget.core.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.geometry.PointMm

class RingOverlayTest {
    companion object {
        @JvmStatic @BeforeAll fun natives() = OpenCvTestSupport.load()
    }

    @Test
    fun `card mm map to image pixels with y flipped`() {
        val p = SimpleCardPlacement(500.0, 400.0, 2.0)
        val px = p.toPx(PointMm(10.0, 20.0))
        assertEquals(520.0, px.x)
        assertEquals(360.0, px.y)
    }

    @Test
    fun `centred guess fits the 1 ring inside the shorter side`() {
        val p = RingOverlay.centredGuess(1940, 2576)
        assertEquals(970.0, p.centreXPx)
        assertEquals(1288.0, p.centreYPx)
        assertTrue(500.0 * p.pxPerMm <= 1940)
    }

    @Test
    fun `rings are drawn on the ring radius and nowhere near the centre gap`() {
        val img = Mat(1000, 1000, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        val placement = SimpleCardPlacement(500.0, 500.0, 1.5)
        RingOverlay.draw(img, placement)
        // The 10 ring (radius 25 mm = 37.5 px) passes through (537, 500).
        val onRing = img.get(500, 537)
        assertTrue(onRing.sum() > 0, "expected ring pixels at the 10 ring radius")
        // Between the 7 and 6 rings, away from labels: should be untouched.
        val between = img.get(500 - 168, 500)
        assertEquals(0.0, between.sum(), "expected no drawing between rings")
        img.release()
    }
}
