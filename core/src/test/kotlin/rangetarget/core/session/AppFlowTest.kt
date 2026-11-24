package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.debug.DebugSink
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.PointMm
import rangetarget.core.image.Rotation
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** The pieces the phone app v1 uses on top of the pipeline: finger loops, lone photos, top-down views. */
class AppFlowTest {
    private fun shot(id: String, x: Double, y: Double) = ShotReading(id, 1, null, PointMm(x, y), null, 9.0, 0, ShotSource.CLEAN, 1.0, emptyList())
    private fun circle(c: PointMm, r: Double) = (0 until 36).map { PointMm(c.x + r * cos(it * Math.PI / 18), c.y + r * sin(it * Math.PI / 18)) }

    @Test
    fun `finger loops collect the shots inside them and leave the rest unassigned`() {
        val aims = listOf(AimPoint(0, PointMm.ORIGIN, "centre"), AimPoint(1, PointMm(-100.0, 100.0), "tapped"))
        val shots = listOf(shot("a", 2.0, -3.0), shot("b", -4.0, 1.0), shot("c", 1.0, 5.0),
            shot("d", -98.0, 96.0), shot("e", -104.0, 102.0), shot("f", 200.0, -200.0))
        val loops = listOf(DrawnLoop(circle(PointMm.ORIGIN, 15.0), 0), DrawnLoop(circle(PointMm(-100.0, 100.0), 15.0), 1))
        val out = DrawnGroups.measure(1, shots, loops, aims, 12.0, expectedShots = 3)
        assertEquals(listOf("a", "b", "c"), out.groups[0].shotIds)
        assertEquals(listOf("d", "e"), out.groups[1].shotIds)
        assertEquals(listOf("f"), out.unassigned.map { it.id })
        assertTrue(FlagCode.HOLE_OUTSIDE_LOOPS in out.unassigned.single().flags)
        assertTrue(out.groups[1].flags.any { it.code == FlagCode.FEWER_HOLES_THAN_FIRED })
        val m = out.groups[1].metrics!!
        assertEquals(-101.0, m.centreMm.x, 1e-9)
        assertEquals(PointMm(-1.0, -1.0), m.offsetFromAimMm)
        assertEquals(m.extremeSpreadMm / 12.0, m.extremeSpreadMrad, 1e-9)
        assertEquals(1, DrawnGroups.proposeAim(circle(PointMm(-90.0, 90.0), 20.0), shots, aims))
        assertNull(DrawnGroups.proposeAim(circle(PointMm(250.0, 250.0), 5.0), emptyList(), aims))
    }

    @Test
    fun `a hole in two loops goes to the loop with the nearer aim point and is flagged`() {
        val aims = listOf(AimPoint(1, PointMm(0.0, 0.0), "tapped"), AimPoint(2, PointMm(40.0, 0.0), "tapped"))
        val shots = listOf(shot("a", 25.0, 0.0))
        val loops = listOf(DrawnLoop(circle(PointMm(5.0, 0.0), 30.0), 1), DrawnLoop(circle(PointMm(35.0, 0.0), 30.0), 2))
        val out = DrawnGroups.measure(1, shots, loops, aims, 12.0)
        assertEquals("r1g2", out.shots.single().groupId)
        assertTrue(FlagCode.HOLE_IN_TWO_LOOPS in out.shots.single().flags)
    }

    @Test
    fun `round observations are questions`() {
        val aims = listOf(AimPoint(1, PointMm(0.0, 0.0), "tapped"), AimPoint(2, PointMm(100.0, 0.0), "tapped"))
        val low = listOf(shot("a", -2.0, -12.0), shot("b", 3.0, -15.0), shot("c", 98.0, -14.0), shot("d", 103.0, -11.0))
        val loops = listOf(DrawnLoop(circle(PointMm(0.0, -10.0), 20.0), 1), DrawnLoop(circle(PointMm(100.0, -10.0), 20.0), 2))
        val text = Observations.round(DrawnGroups.measure(1, low, loops, aims, 12.0).groups)
        assertTrue(text.startsWith("Every group landed low") && text.endsWith("?"), text)
        assertEquals("No groups circled yet.", Observations.round(emptyList()))
    }

    @Test
    fun `a lone photo lists every hole on the real card`() {
        OpenCvTestSupport.load()
        val f = File(OpenCvTestSupport.repoRoot, "test_data/real/2026-11-21_card01_final.jpg")
        val r = LonePhoto.read(Rotation.LEFT.apply(Imgcodecs.imread(f.path)))
        assertNotNull(r)
        assertEquals(ViewBox.DEFAULT.widthPx, r!!.view.cols())
        assertEquals(ViewBox.DEFAULT.heightPx, r.view.rows())
        assertTrue(r.shots.size in 100..140, "holes ${r.shots.size}")
        assertTrue(r.shots.all { it.score in 0..10 })
        r.view.release()
    }

    @Test
    fun `session hands out top-down views, and loops drawn where the paint is give the same groups`() {
        OpenCvTestSupport.load()
        val folder = File(OpenCvTestSupport.repoRoot, "test_data/synthetic/realistic_sticker_s0202")
        val views = LinkedHashMap<String, Pair<Int, Int>>()
        val analyzer = SessionAnalyzer(null, DebugSink.NONE, DebugSink { name, m: Mat -> views[name] = m.cols() to m.rows(); m.release() })
        fun img(name: String) = Imgcodecs.imread(File(folder, name).path)
        val base = analyzer.baseline(img("baseline.jpg"), "baseline.jpg")
        val aims = base.aimPoints.mapNotNull { a -> a.number?.let { AimPoint(it, a.centreMm, "found") } }
        val r = analyzer.round(img("round_01.jpg"), "round_01.jpg", 1)
        analyzer.release()
        assertEquals(listOf("r00", "r01"), views.keys.toList())
        assertTrue(views.values.all { it == ViewBox.DEFAULT.widthPx to ViewBox.DEFAULT.heightPx })

        val paintLoops = r.groups.map { g -> r.ink.first { it.id == g.loopId } to g }
        val drawn = paintLoops.map { (ink, g) -> DrawnLoop(ink.outlineMm, g.aimPointNumber) }
        val out = DrawnGroups.measure(1, r.shots, drawn, aims, 12.0)
        val same = r.shots.count { s -> s.groupId != null && out.shots.first { it.id == s.id }.groupId?.let { gid ->
            r.groups.indexOfFirst { it.id == s.groupId } == out.groups.indexOfFirst { it.id == gid } } == true }
        val inGroups = r.shots.count { it.groupId != null }
        assertTrue(inGroups > 10 && same >= inGroups * 0.95, "same group $same of $inGroups")

        // Hidden-shot hints: older holes never sit under a new shot.
        for (h in r.hints.filter { it.kind == "older" }) assertTrue(r.shots.none { (it.centreMm - h.centreMm).radius < SessionAnalyzer.HINT_CLEAR_MM })
        assertTrue(r.hints.all { it.kind == "older" || it.kind == "big" })
    }
}
