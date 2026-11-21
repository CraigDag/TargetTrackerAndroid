package rangetarget.core.geometry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import rangetarget.core.OpenCvTestSupport
import java.io.File

class CardGeometryTest {
    @ParameterizedTest(name = "ring {0} is {1} mm across")
    @CsvSource("10,50", "9,100", "8,150", "7,200", "6,250", "5,300", "4,350", "3,400", "2,450", "1,500")
    fun `ring diameters follow the ISSF card`(ring: Int, diameterMm: Double) {
        assertEquals(diameterMm, CardGeometry.ringDiameterMm(ring))
        assertEquals(diameterMm / 2, CardGeometry.ringRadiusMm(ring))
    }

    @Test
    fun `the black aiming area is the 7 ring, 200 mm`() {
        assertEquals(200.0, CardGeometry.blackDiameterMm)
        assertEquals(100.0, CardGeometry.blackRadiusMm)
    }

    @Test
    fun `the 1 ring fits on the paper`() {
        assertEquals(500.0, CardGeometry.outerRingDiameterMm)
        assertTrue(CardGeometry.outerRingDiameterMm < minOf(CardGeometry.PAPER_WIDTH_MM, CardGeometry.PAPER_HEIGHT_MM))
    }

    @Test
    fun `rings outside 1 to 10 are rejected`() {
        assertThrows<IllegalArgumentException> { CardGeometry.ringDiameterMm(0) }
        assertThrows<IllegalArgumentException> { CardGeometry.ringDiameterMm(11) }
    }

    @ParameterizedTest(name = "radius {0} mm is in ring {1}")
    @CsvSource("0,10", "24.9,10", "25,10", "25.1,9", "99.9,7", "100,7", "100.1,6", "250,1", "250.1,0", "400,0")
    fun `ringAt finds the ring a point lies in`(radiusMm: Double, ring: Int) {
        assertEquals(ring, CardGeometry.ringAt(radiusMm))
    }

    @Test
    fun `points use the card frame with y up`() {
        val p = PointMm(30.0, 40.0)
        assertEquals(50.0, p.radius)
        assertEquals(9, CardGeometry.ringAt(p)) // exactly on the 9 ring edge counts as a 9
        assertTrue(CardGeometry.isInBlack(PointMm(0.0, 99.0)))
        assertFalse(CardGeometry.isInBlack(PointMm(0.0, 101.0)))
    }

    @Test
    fun `the checked-in geometry file for the Python tools matches`() {
        val file = File(OpenCvTestSupport.repoRoot, "test_data/card_geometry.json")
        assertTrue(file.isFile, "missing ${file.path}; run: rangetarget geometry --out test_data/card_geometry.json")
        assertEquals(CardGeometry.exportJson(), file.readText(),
            "test_data/card_geometry.json is out of date; regenerate with: rangetarget geometry --out test_data/card_geometry.json")
    }
}
