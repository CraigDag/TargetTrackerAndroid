package rangetarget.core.groundtruth

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.geometry.PointMm
import java.io.File

class GroundTruthTest {
    private val exampleFile = File(OpenCvTestSupport.repoRoot, "test_data/synthetic/example/round_01.json")

    @Test
    fun `reads the shared example file`() {
        val gt = GroundTruth.read(exampleFile)
        assertEquals(1, gt.round)
        assertEquals(12.0, gt.roundInfo!!.distanceM)
        assertEquals(2, gt.aimPoints.size)
        assertEquals(AimPointKind.HAND_DRAWN, gt.aimPoints[1].kind)
        assertEquals(PointMm(-127.3, 127.3), gt.aimPoints[0].centreMm)
        assertEquals(6, gt.shots.size)
        val flyer = gt.shots.last()
        assertTrue(flyer.flyer)
        assertNull(flyer.groupId)
        assertEquals("r1g2s1", gt.shots[4].throughShotId)
        assertEquals(listOf("orange", "yellow"), gt.loops.map { it.colour })
        assertEquals(9, gt.distortion.homographyMmToPx.size)
    }

    @Test
    fun `writing and reading again gives the same data`() {
        val gt = GroundTruth.read(exampleFile)
        assertEquals(gt, GroundTruth.parse(gt.toJson()))
    }

    @Test
    fun `keys are snake_case`() {
        val json = GroundTruth.read(exampleFile).toJson()
        assertTrue("\"homography_mm_to_px\"" in json)
        assertTrue("\"aim_point_number\"" in json)
    }

    @Test
    fun `unknown keys are rejected so the formats cannot drift`() {
        val bad = exampleFile.readText().replaceFirst("\"seed\": 0,", "\"seed\": 0, \"surprise\": 1,")
        assertThrows<SerializationException> { GroundTruth.parse(bad) }
    }

    @Test
    fun `other schema versions are rejected`() {
        val bad = exampleFile.readText().replaceFirst("\"schema_version\": 1", "\"schema_version\": 99")
        assertThrows<IllegalArgumentException> { GroundTruth.parse(bad) }
    }
}
