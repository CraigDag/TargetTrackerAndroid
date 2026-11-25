package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.groundtruth.GroundTruth
import rangetarget.core.groundtruth.TruthCheck
import java.io.File

/** Carrying on from saved state gives the same round as reading every photo in one go. */
class SessionStateTest {
    @Test
    fun `restored analyzer reads the next round the same`(@TempDir tmp: File) {
        OpenCvTestSupport.load()
        val folder = File(OpenCvTestSupport.repoRoot, "test_data/synthetic/realistic_sticker_s0202")
        val truths = (0..2).map { GroundTruth.read(File(folder, if (it == 0) "baseline.json" else "round_%02d.json".format(it))) }
        val plan = TruthCheck.planFrom(truths)
        fun img(name: String) = Imgcodecs.imread(File(folder, name).path)

        val straight = SessionAnalyzer(plan)
        straight.baseline(img("baseline.jpg"), "baseline.jpg")
        straight.round(img("round_01.jpg"), "round_01.jpg", 1)
        val want = straight.round(img("round_02.jpg"), "round_02.jpg", 2)
        straight.release()

        assertNull(SessionAnalyzer.restore(tmp, plan), "nothing saved yet")
        val first = SessionAnalyzer(plan).also { it.stateDir = tmp }
        first.baseline(img("baseline.jpg"), "baseline.jpg")
        first.round(img("round_01.jpg"), "round_01.jpg", 1)
        first.release()

        val again = SessionAnalyzer.restore(tmp, plan)
        assertNotNull(again)
        assertEquals(1, again!!.photosRead)
        val got = again.round(img("round_02.jpg"), "round_02.jpg", 2)
        again.release()
        assertEquals(want.shots.map { it.centreMm }, got.shots.map { it.centreMm })
        assertEquals(want.groups.map { it.shotIds }, got.groups.map { it.shotIds })
        assertEquals(2, SessionAnalyzer.restore(tmp, plan)!!.photosRead)
        // Only the latest photo's files are kept.
        assertEquals(setOf("state.json", "view_02.png", "holes_02.png"), tmp.list()!!.toSet())
    }
}
