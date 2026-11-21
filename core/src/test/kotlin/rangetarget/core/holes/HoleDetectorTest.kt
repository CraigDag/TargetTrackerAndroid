package rangetarget.core.holes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.SyntheticCard
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import kotlin.math.hypot
import kotlin.math.roundToInt

class HoleDetectorTest {
    companion object {
        @JvmStatic @BeforeAll fun natives() = OpenCvTestSupport.load()
        val paper = run {
            val x = CardGeometry.PAPER_WIDTH_MM / 2; val y = CardGeometry.PAPER_HEIGHT_MM / 2
            listOf(PointMm(-x, y), PointMm(x, y), PointMm(x, -y), PointMm(-x, -y))
        }
    }

    private fun nearest(holes: List<Hole>, p: PointMm) = holes.minOf { hypot(it.centreMm.x - p.x, it.centreMm.y - p.y) }

    @Test
    fun `finds every hole on the beige and in the black, and nothing else`() {
        val truth = SyntheticCard.scatteredHoles()
        val view = SyntheticCard.flat(truth, pen = true)
        val det = HoleDetector.detect(view, paper)
        assertEquals(truth.size, det.holes.size, "found ${det.holes.map { it.centreMm }}")
        for (p in truth) assertTrue(nearest(det.holes, p) < 0.8, "hole at $p missed or off by ${nearest(det.holes, p)}")
        assertTrue(det.holes.all { it.confident }, "unexpected flags: ${det.holes.filter { !it.confident }}")
        assertEquals(HoleZone.BLACK, det.holes.first { hypot(it.centreMm.x - 12.0, it.centreMm.y + 8.0) < 1 }.zone)
    }

    @Test
    fun `pen loops, ring lines and a blank card give no holes`() {
        val det = HoleDetector.detect(SyntheticCard.flat(emptyList(), pen = true), paper)
        assertEquals(0, det.holes.size, "false holes at ${det.holes.map { it.centreMm }}")
    }

    @Test
    fun `two touching holes become one flagged blob with two estimated centres`() {
        val singles = SyntheticCard.scatteredHoles()
        val pair = listOf(PointMm(-150.0, -60.0), PointMm(-144.0, -60.0))
        val det = HoleDetector.detect(SyntheticCard.flat(singles + pair), paper)
        val merged = det.holes.filter { FlagCode.OVERLAPPING_HOLES in it.flags }
        assertEquals(2, merged.size)
        assertEquals(1, merged.map { it.blobId }.toSet().size)
        for (p in pair) assertTrue(nearest(merged, p) < 2.0, "estimated centre off for $p")
    }

    @Test
    fun `hole cut by the paper edge is flagged`() {
        val edgeHole = PointMm(100.0, CardGeometry.PAPER_HEIGHT_MM / 2 - 2.0)
        val det = HoleDetector.detect(SyntheticCard.flat(SyntheticCard.scatteredHoles() + edgeHole), paper)
        val h = det.holes.minByOrNull { hypot(it.centreMm.x - edgeHole.x, it.centreMm.y - edgeHole.y) }!!
        assertTrue(FlagCode.HOLE_ON_PAPER_EDGE in h.flags, "flags were ${h.flags}")
    }

    @Test
    fun `a vivid sticker becomes an unresolvable cluster and its holes are not listed`() {
        val holes = SyntheticCard.scatteredHoles()
        val view = SyntheticCard.flat(holes)
        val c = PointMm(210.0, -60.0)
        val px = view.toPx(c)
        Imgproc.circle(view.image, px, (35 * view.pxPerMm).roundToInt(), Scalar(40.0, 230.0, 200.0), -1) // fluorescent green
        for (d in listOf(-12.0, 0.0, 12.0)) Imgproc.circle(view.image, Point(px.x + d * 4, px.y), 16, SyntheticCard.HOLE_ON_BEIGE, -1)
        val stickers = StickerFinder.find(view)
        assertEquals(1, stickers.size)
        assertEquals(c.x, stickers[0].centreMm.x, 2.0); assertEquals(c.y, stickers[0].centreMm.y, 2.0)
        val det = HoleDetector.detect(view, paper, stickers)
        assertEquals(holes.size, det.holes.size)
        assertEquals(1, det.clusters.size)
        assertTrue(det.clusters[0].roughShotsAtLeast >= 2, "cluster ${det.clusters[0]}")
    }
}
