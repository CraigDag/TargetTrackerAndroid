package rangetarget.core.card

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.image.Rotation
import java.io.File

/**
 * Regression check on the one real photo. There is no ground truth, so these
 * are ranges around what was checked by eye in Phase 1 (see the PR for the
 * debug images). If a change moves a number out of range, look at the images.
 */
class RealPhotoTest {
    companion object {
        lateinit var reading: CardReading
        @JvmStatic @BeforeAll fun read() {
            OpenCvTestSupport.load()
            val f = File(OpenCvTestSupport.repoRoot, "test_data/real/2026-11-21_card01_final.jpg")
            val photo = Rotation.LEFT.apply(Imgcodecs.imread(f.path))
            reading = CardReader.read(photo)!!
        }
    }

    @Test
    fun `rings fit within a millimetre`() {
        assertTrue(reading.card.ringMissP95Mm < 1.0, "95% miss ${reading.card.ringMissP95Mm}")
        assertTrue(reading.card.ringPointsUsed > 3500)
    }

    @Test
    fun `paper is about 55 by 52 cm and all edges are found`() {
        assertEquals(4, reading.card.paperEdgesFound.size)
        assertEquals(550.0, reading.card.paperWidthMm!!, 6.0)
        assertEquals(520.0, reading.card.paperHeightMm!!, 6.0)
    }

    @Test
    fun `hole count and sticker cluster stay in the range checked by eye`() {
        assertTrue(reading.holes.size in 110..130, "holes: ${reading.holes.size}")
        assertTrue(reading.holes.count { it.confident } >= 75)
        assertEquals(1, reading.clusters.size)
        assertTrue(reading.clusters[0].roughShotsAtLeast in 40..70)
        // The black has 16 or so holes, all checked by eye.
        assertTrue(reading.holes.count { it.zone != rangetarget.core.holes.HoleZone.BEIGE } in 13..20)
    }
}
