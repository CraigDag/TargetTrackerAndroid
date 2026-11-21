package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.groundtruth.GroundTruth
import rangetarget.core.groundtruth.TruthCheck
import java.io.File

/**
 * Runs the whole pipeline on the fixed synthetic session and checks it against
 * the known answers. The bars are loose sanity levels for Phase 3; the real
 * validation targets (docs/SPEC.md) are checked in Phase 4.
 */
class SessionEndToEndTest {
    @Test
    fun `realistic sticker session comes out close to the truth`() {
        OpenCvTestSupport.load()
        val folder = File(OpenCvTestSupport.repoRoot, "test_data/synthetic/realistic_sticker_s0202")
        val truths = (0..4).map { GroundTruth.read(File(folder, if (it == 0) "baseline.json" else "round_%02d.json".format(it))) }
        val analyzer = SessionAnalyzer(TruthCheck.planFrom(truths))
        fun img(name: String) = Imgcodecs.imread(File(folder, name).path)

        val base = analyzer.baseline(img("baseline.jpg"), "baseline.jpg")
        val aim = TruthCheck.aimPoints(base.aimPoints, truths[0])
        assertEquals(aim.trueCount, aim.matched, "aim points found")
        assertTrue(aim.medianErrorMm!! <= 1.5, "aim point error ${aim.medianErrorMm}")
        assertTrue(aim.numbersRight >= aim.trueCount - 1, "numbers read ${aim.numbersRight}")

        var visible = 0; var matched = 0; var right = 0; var falseHoles = 0
        val errors = ArrayList<Double>()
        for (n in 1..4) {
            val r = analyzer.round(img("round_%02d.jpg".format(n)), "round_%02d.jpg".format(n), n)
            assertTrue(r.alignment!!.leftoverP95Mm < 1.0, "round $n alignment ${r.alignment}")
            val c = TruthCheck.round(r, truths[n])
            visible += c.trueVisible; matched += c.matched; right += c.assignedRight; falseHoles += c.falseHoles.size
            c.medianErrorMm?.let { errors += it }
        }
        val result = analyzer.result()
        analyzer.release()
        assertEquals(4, result.rounds.size)
        val recall = matched.toDouble() / visible
        val assigned = right.toDouble() / matched
        assertTrue(recall >= 0.85, "found $matched of $visible")
        assertTrue(assigned >= 0.85, "right group $right of $matched")
        assertTrue(falseHoles <= 10, "false holes $falseHoles")
        assertTrue(errors.sorted()[errors.size / 2] <= 1.5, "position errors $errors")
        assertTrue(result.rounds.all { r -> r.groups.any { it.metrics != null } })
    }
}
