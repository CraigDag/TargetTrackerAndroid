package rangetarget.core.groundtruth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.geometry.CardGeometry
import java.io.File

/**
 * Reads files written by the Python session generator (tools/rangetools/synth),
 * to prove both sides agree on the ground-truth format and the card frame.
 */
class GeneratedSessionTest {
    private val folder = File(OpenCvTestSupport.repoRoot, "test_data/synthetic/realistic_sticker_s0202")

    @Test
    fun `reads every generated ground-truth file in the fixed set`() {
        val files = File(OpenCvTestSupport.repoRoot, "test_data/synthetic").walk()
            .filter { it.extension == "json" && it.parentFile.name != "example" }.toList()
        assertTrue(files.size >= 10, "expected the fixed synthetic set, found ${files.size} files")
        for (f in files) {
            val gt = GroundTruth.read(f)
            assertTrue(File(f.parentFile, gt.image.file).isFile, "missing image for $f")
            assertEquals(gt, GroundTruth.parse(gt.toJson()), "round trip changed $f")
        }
    }

    @Test
    fun `a generated round agrees with its image and the card geometry`() {
        OpenCvTestSupport.load()
        val gt = GroundTruth.read(File(folder, "round_02.json"))
        assertEquals(2, gt.round)
        assertEquals("realistic", gt.preset)

        val img = Imgcodecs.imread(File(folder, gt.image.file).path)
        assertEquals(gt.image.widthPx, img.cols())
        assertEquals(gt.image.heightPx, img.rows())

        // Each file lists everything so far; this round's groups each have a loop
        // and their shots, and every shot sits on the paper.
        val newGroups = gt.groups.filter { it.round == 2 }
        assertTrue(newGroups.isNotEmpty())
        for (g in newGroups) {
            assertEquals(1, gt.loops.count { it.groupId == g.id && it.round == 2 })
            assertEquals(g.firedShots, gt.shots.count { it.id.startsWith(g.id + "s") })
        }
        for (s in gt.shots) {
            assertTrue(kotlin.math.abs(s.centreMm.x) < CardGeometry.PAPER_WIDTH_MM / 2)
            assertTrue(kotlin.math.abs(s.centreMm.y) < CardGeometry.PAPER_HEIGHT_MM / 2)
        }

        // The homography takes the card centre into the photo, and the black
        // disc comes out darker there than the beige paper near a corner.
        val h = gt.distortion.homographyMmToPx
        fun px(x: Double, y: Double): Pair<Int, Int> {
            val w = h[6] * x + h[7] * y + h[8]
            return Pair(((h[0] * x + h[1] * y + h[2]) / w).toInt(), ((h[3] * x + h[4] * y + h[5]) / w).toInt())
        }
        fun grey(p: Pair<Int, Int>): Double = img.get(p.second, p.first).take(3).average()
        val centre = px(60.0, 60.0)        // inside the black, away from print lines
        val paper = px(255.0, -245.0)      // beige near a corner
        assertTrue(centre.first in 0 until img.cols() && centre.second in 0 until img.rows())
        assertTrue(grey(centre) + 60 < grey(paper), "black ${grey(centre)} vs paper ${grey(paper)}")
        img.release()
    }
}
