package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rangetarget.core.geometry.PointMm
import kotlin.random.Random

class PatternsTest {
    private fun groups(n: Int, sx: Double, sy: Double, off: PointMm, distance: Double = 12.0, seed: Int = 1): List<PatternGroup> {
        val rnd = Random(seed)
        fun g() = (1..12).sumOf { rnd.nextDouble() } - 6.0
        return (0 until n).map { k ->
            val aim = PointMm(-150.0 + 100 * k, 150.0)
            PatternGroup((0 until 5).map { PointMm(aim.x + off.x + sx * g(), aim.y + off.y + sy * g()) }, aim, distance)
        }
    }

    @Test fun tooFewShots() {
        val p = Patterns(groups(2, 5.0, 5.0, PointMm.ORIGIN))
        assertEquals("Not enough shots yet", p.notes().single().title)
    }

    @Test fun sideToSideAndLowLeft() {
        val p = Patterns(groups(6, 10.0, 4.0, PointMm(-15.0, -12.0)))
        val titles = p.notes().map { it.title }
        assertTrue("Shots spread side to side" in titles, titles.toString())
        assertTrue("Groups land off the aim point" in titles, titles.toString())
        assertTrue(p.notes().first { it.title == "Groups land off the aim point" }.text.contains("low left"))
    }

    @Test fun upAndDownCentred() {
        val titles = Patterns(groups(6, 3.0, 9.0, PointMm.ORIGIN)).notes().map { it.title }
        assertTrue("Shots spread up and down" in titles, titles.toString())
        assertTrue("Groups centre on the aim point" in titles, titles.toString())
    }

    @Test fun distancesShareOneScale() {
        // The same angular spread at 25 m is twice as many mm on the card; at 12 m reference it reads the same.
        val near = Patterns(groups(6, 6.0, 6.0, PointMm.ORIGIN, 12.0, seed = 3))
        val far = Patterns(groups(6, 12.5, 12.5, PointMm.ORIGIN, 25.0, seed = 3))
        assertEquals(near.sideSpread, far.sideSpread, 0.01)
    }

    @Test fun findsAStray() {
        val base = groups(4, 3.0, 3.0, PointMm.ORIGIN)
        val g = base[0]
        val withStray = listOf(g.copy(shotsMm = g.shotsMm + PointMm(g.aimMm!!.x + 40, g.aimMm!!.y))) + base.drop(1)
        assertEquals(1, Patterns(withStray).strays)
    }
}
