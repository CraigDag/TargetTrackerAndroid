package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.geometry.PointMm

/**
 * The loop-reading fixes from Phase 4 validation: loops left open, loops broken
 * where they run along an old loop of the same colour, loops closed by the
 * paper edge, and partly-closed loops widened to their whole outline.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InkReaderTest {
    private val box = ViewBox(4.0, -100.0, 100.0, -100.0, 100.0)
    private val ppm = box.pxPerMm

    @BeforeAll fun load() = OpenCvTestSupport.load()

    private fun blank() = Mat.zeros(box.heightPx, box.widthPx, CvType.CV_8UC1)

    /** A circular arc of a 3 mm stroke from [fromDeg] to [toDeg] (anticlockwise, image angles). */
    private fun arc(m: Mat, c: PointMm, rMm: Double, fromDeg: Double, toDeg: Double) {
        val r = (rMm * ppm).toInt()
        Imgproc.ellipse(m, box.toPx(c), Size(r.toDouble(), r.toDouble()), 0.0, fromDeg, toDeg, Scalar(255.0), (3 * ppm).toInt())
    }

    private fun inside(m: Mat, p: PointMm): Boolean { val q = box.toPx(p); return m.get(q.y.toInt(), q.x.toInt())[0] > 0 }

    @Test
    fun `a loop left open by a wide gap is still a loop`() {
        val ink = blank(); arc(ink, PointMm.ORIGIN, 25.0, 30.0, 330.0)  // a 60 degree gap, ~26 mm
        val loops = InkReader.openLoops(ink, blank(), ppm)
        assertEquals(1, loops.size)
        assertTrue(inside(loops[0], PointMm.ORIGIN))
        assertTrue(inside(loops[0], PointMm(20.0, 0.0)), "the gap side is closed with a straight line")
    }

    @Test
    fun `a short stroke or a number is not a loop`() {
        val ink = blank()
        Imgproc.line(ink, box.toPx(PointMm(-30.0, 0.0)), box.toPx(PointMm(30.0, 5.0)), Scalar(255.0), 12)
        arc(ink, PointMm(0.0, 60.0), 6.0, 0.0, 300.0) // small, like a handwritten digit
        assertTrue(InkReader.openLoops(ink, blank(), ppm).isEmpty())
    }

    @Test
    fun `a loop broken where it runs along an old loop of the same colour is joined up`() {
        val ink = blank(); arc(ink, PointMm.ORIGIN, 25.0, 10.0, 130.0); arc(ink, PointMm.ORIGIN, 25.0, 230.0, 310.0)
        val old = blank(); arc(old, PointMm(0.0, -2.0), 26.0, 125.0, 235.0); arc(old, PointMm(0.0, -2.0), 26.0, 305.0, 375.0)
        assertTrue(InkReader.openLoops(ink, blank(), ppm).isEmpty(), "without the old ink the two arcs are too far apart")
        val loops = InkReader.openLoops(ink, old, ppm)
        assertEquals(1, loops.size)
        assertTrue(inside(loops[0], PointMm.ORIGIN))
    }

    @Test
    fun `a region only partly closed is widened to the whole loop`() {
        val stroke = blank(); arc(stroke, PointMm.ORIGIN, 30.0, 300.0, 600.0)  // open at the top (image angles run clockwise)
        // What a pass found: the stroke, with only its bottom part closed off by a chord.
        val region = stroke.clone()
        Imgproc.ellipse(region, box.toPx(PointMm.ORIGIN), Size(30 * ppm, 30 * ppm), 0.0, 20.0, 160.0, Scalar(255.0), -1)
        assertFalse(inside(region, PointMm(0.0, 15.0)))
        val support = Mat(); Imgproc.dilate(stroke, support, ChangeDetector.disc((3 * ppm).toInt() or 1))
        val widened = InkReader.fillToHull(region, stroke, support, ppm)
        assertTrue(inside(widened, PointMm(0.0, 15.0)), "the top of the loop is now inside")
    }

    @Test
    fun `a loop closed by the paper edge is accepted, a corner cut off is not`() {
        // Off the paper: a strip down the left and along the top (a corner of the card).
        val offCard = blank()
        Imgproc.rectangle(offCard, Point(0.0, 0.0), Point(20 * ppm, box.heightPx.toDouble()), Scalar(255.0), -1)
        Imgproc.rectangle(offCard, Point(0.0, 0.0), Point(box.widthPx.toDouble(), 20 * ppm), Scalar(255.0), -1)
        // A loop running off the left edge: mostly bounded by its ink.
        val loop = blank(); Imgproc.ellipse(loop, box.toPx(PointMm(-60.0, 0.0)), Size(25 * ppm, 40 * ppm), 0.0, 0.0, 360.0, Scalar(255.0), -1)
        assertFalse(InkReader.mostlyEdge(loop, offCard, ppm))
        // A stroke cutting off the corner: two of its three sides are paper edge.
        val corner = blank()
        Imgproc.fillPoly(corner, listOf(org.opencv.core.MatOfPoint(box.toPx(PointMm(-100.0, 100.0)), box.toPx(PointMm(-20.0, 100.0)), box.toPx(PointMm(-100.0, 20.0)))), Scalar(255.0))
        assertTrue(InkReader.mostlyEdge(corner, offCard, ppm))
    }
}
