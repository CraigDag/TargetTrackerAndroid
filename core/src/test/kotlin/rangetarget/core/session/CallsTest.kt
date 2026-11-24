package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import rangetarget.core.geometry.PointMm

class CallsTest {
    @Test
    fun `each call goes to the nearest group once, far calls are left out`() {
        val middles = listOf(PointMm(0.0, 0.0), PointMm(100.0, 0.0), null)
        val calls = listOf(PointMm(95.0, 5.0), PointMm(3.0, -4.0), PointMm(-200.0, 0.0))
        val m = Calls.match(middles, calls)
        assertEquals(listOf(0, 1), m.map { it.groupIndex })
        assertEquals(PointMm(-3.0, 4.0), m[0].landedFromCall)
        assertEquals("5 mm high left of your call", Calls.words(m[0]))
    }

    @Test
    fun `a call on the middle reads as right where you called it`() {
        val m = Calls.match(listOf(PointMm(10.0, 10.0)), listOf(PointMm(10.5, 9.5)))
        assertEquals("right where you called it", Calls.words(m.single()))
    }
}
