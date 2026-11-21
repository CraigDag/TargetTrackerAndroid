package rangetarget.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import org.opencv.objdetect.RefineParameters

/** The desktop natives load and the modules core relies on (including ArUco) actually run. */
class OpenCvNativeSmokeTest {
    companion object {
        @JvmStatic @BeforeAll fun natives() = OpenCvTestSupport.load()
    }

    @Test
    fun `natives are the pinned OpenCV version`() {
        assertTrue(Core.VERSION.startsWith("4.9."), "expected OpenCV 4.9.x, got ${Core.VERSION}")
    }

    @Test
    fun `ArUco markers can be generated and found again`() {
        val dict = Objdetect.getPredefinedDictionary(Objdetect.DICT_4X4_50)
        val marker = Mat()
        Objdetect.generateImageMarker(dict, 7, 200, marker)
        // Put it on a white page with a quiet zone, as a printed sticker would be.
        val page = Mat(400, 400, CvType.CV_8UC1, Scalar(255.0))
        marker.copyTo(page.submat(100, 300, 100, 300))

        val detector = ArucoDetector(dict, DetectorParameters(), RefineParameters())
        val corners = mutableListOf<Mat>()
        val ids = MatOfInt()
        detector.detectMarkers(page, corners, ids)

        assertEquals(listOf(7), ids.toList())
        assertEquals(1, corners.size)
    }
}
