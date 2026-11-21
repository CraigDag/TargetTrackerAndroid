package rangetarget.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.geometry.CardGeometry
import java.io.File

class MainTest {
    private fun runCaptured(vararg args: String): Pair<Int, String> {
        val lines = mutableListOf<String>()
        val code = run(args.toList()) { lines += it }
        return code to lines.joinToString("\n")
    }

    @Test
    fun `version shows the OpenCV version`() {
        val (code, text) = runCaptured("version")
        assertEquals(0, code)
        assertTrue("OpenCV 4.9" in text, text)
    }

    @Test
    fun `geometry writes the same JSON core exports`(@TempDir dir: File) {
        val target = File(dir, "g.json")
        val (code, _) = runCaptured("geometry", "--out", target.path)
        assertEquals(0, code)
        assertEquals(CardGeometry.exportJson(), target.readText())
    }

    @Test
    fun `smoke draws rings on a photo and writes it out`(@TempDir dir: File) {
        OpenCvNatives.load()
        val photo = File(dir, "card.jpg")
        Imgcodecs.imwrite(photo.path, Mat(600, 400, CvType.CV_8UC3, Scalar(150.0, 170.0, 190.0)))
        val outDir = File(dir, "out")
        val (code, text) = runCaptured("smoke", photo.path, "--out", outDir.path)
        assertEquals(0, code, text)
        val result = Imgcodecs.imread(File(outDir, "card_rings.jpg").path)
        assertEquals(400, result.cols())
        assertEquals(600, result.rows())
    }

    @Test
    fun `smoke runs on the real test photo`(@TempDir dir: File) {
        val photo = File(System.getProperty("rangetarget.repoRoot"), "test_data/real/2026-11-21_card01_final.jpg")
        // This photo was taken turned a quarter to the right; turning it left makes it upright.
        val (code, text) = runCaptured("smoke", photo.path, "--rotate", "left", "--out", dir.path)
        assertEquals(0, code, text)
        assertTrue("2576 x 1940" in text, text)
    }

    @Test
    fun `bad command lines give a usage error`() {
        assertThrows<UsageException> { run(listOf("nonsense")) }
        assertThrows<UsageException> { run(listOf("smoke")) }
        assertThrows<UsageException> { run(listOf("smoke", "/no/such/file.jpg")) }
        assertThrows<UsageException> { run(listOf("geometry", "--out")) }
        assertThrows<UsageException> { run(listOf("smoke", "x.jpg", "--rotate", "sideways")) }
    }

    @Test
    fun `card reads the real photo and writes debug images, card json and holes csv`(@TempDir dir: File) {
        val photo = File(System.getProperty("rangetarget.repoRoot"), "test_data/real/2026-11-21_card01_final.jpg")
        val (code, text) = runCaptured("card", photo.path, "--rotate", "left", "--out", dir.path)
        assertEquals(0, code, text)
        for (n in listOf("01_input_upright", "02_black_disc", "03_ring_fit", "04_paper_edges", "05_flat",
                "06_flat_with_rings", "07_hole_pixels", "08_holes", "09_holes_on_photo")) {
            assertTrue(File(dir, "$n.jpg").isFile, "missing $n.jpg")
        }
        val reading = rangetarget.core.RangeJson.decodeFromString(
            rangetarget.core.card.CardReading.serializer(), File(dir, "card.json").readText())
        assertEquals(reading.holes.size + 1, File(dir, "holes.csv").readLines().size)
        assertTrue("Holes:" in text, text)
    }

    @Test
    fun `card needs a photo`() {
        assertThrows<UsageException> { run(listOf("card")) {} }
    }
}
