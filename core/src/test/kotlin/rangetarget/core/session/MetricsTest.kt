package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm

class MetricsTest {
    private fun shot(id: String, x: Double, y: Double, score: Int = 10) =
        ShotReading(id, 1, "g", PointMm(x, y), null, 9.0, score, ShotSource.CLEAN, 1.0, emptyList())

    @Test
    fun `square group of four`() {
        val shots = listOf(shot("a", 0.0, 0.0), shot("b", 30.0, 0.0), shot("c", 0.0, 40.0), shot("d", 30.0, 40.0, 8))
        val m = Metrics.group(shots, aim = PointMm(0.0, 0.0), distanceM = 12.0)!!
        assertEquals(4, m.shots)
        assertEquals(50.0, m.extremeSpreadMm, 1e-9)       // the diagonal
        assertEquals(25.0, m.meanRadiusMm, 1e-9)          // every corner is 25 mm from the middle
        assertEquals(30.0, m.horizontalSpreadMm, 1e-9)
        assertEquals(40.0, m.verticalSpreadMm, 1e-9)
        assertEquals(PointMm(15.0, 20.0), m.offsetFromAimMm)
        assertEquals("25 mm high right", m.offsetWords)
        assertEquals(38, m.totalScore)
        assertEquals(50.0 / 12.0, m.extremeSpreadMrad, 1e-9)
    }

    @Test
    fun `angles scale with distance`() {
        val shots = listOf(shot("a", 0.0, 0.0), shot("b", 50.0, 0.0))
        assertEquals(2.0, Metrics.group(shots, null, 25.0)!!.extremeSpreadMrad, 1e-9)
        assertNull(Metrics.group(shots, null, 25.0)!!.offsetFromAimMm)
        assertNull(Metrics.group(emptyList(), null, 12.0))
    }

    @Test
    fun `offset words`() {
        assertEquals("on the aim point", Metrics.words(PointMm(1.0, -1.0)))
        assertEquals("10 mm low", Metrics.words(PointMm(1.0, -10.0)))
        assertEquals("14 mm low left", Metrics.words(PointMm(-10.0, -10.0)))
        assertEquals("6 mm right", Metrics.words(PointMm(6.0, 0.0)))
    }

    @Test
    fun `a hole touching a ring line scores the higher ring`() {
        val r9 = CardGeometry.ringRadiusMm(9)
        // Centre 3 mm outside the 9 line, 9 mm hole: its edge still crosses the line.
        assertEquals(9, CardGeometry.scoreByEdge(PointMm(r9 + 3.0, 0.0), 9.0))
        // Centre 6 mm outside: the edge is clear of the line, so it's an 8.
        assertEquals(8, CardGeometry.scoreByEdge(PointMm(r9 + 6.0, 0.0), 9.0))
        assertEquals(10, CardGeometry.scoreByEdge(PointMm(0.0, 0.0), 9.0))
        assertEquals(0, CardGeometry.scoreByEdge(PointMm(400.0, 0.0), 9.0))
    }
}
