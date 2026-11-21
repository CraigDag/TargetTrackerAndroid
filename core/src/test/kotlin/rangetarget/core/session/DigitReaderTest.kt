package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.OpenCvTestSupport

class DigitReaderTest {
    @Test
    fun `reads a hand-ish digit written in a font it did not train on`() {
        OpenCvTestSupport.load()
        for (n in 1..8) {
            val m = Mat.zeros(120, 120, CvType.CV_8UC1)
            Imgproc.putText(m, n.toString(), Point(30.0, 95.0), Imgproc.FONT_HERSHEY_COMPLEX, 3.0, Scalar(255.0), 5)
            val best = (1..9).maxByOrNull { DigitReader.score(m, it) }
            assertEquals(n, best, "digit $n")
        }
    }
}
