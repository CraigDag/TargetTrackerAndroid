package rangetarget.core.image

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import rangetarget.core.OpenCvTestSupport

class RotationTest {
    companion object {
        @JvmStatic @BeforeAll fun natives() = OpenCvTestSupport.load()
    }

    /** 3 wide × 2 tall, with one marked pixel in the top-right corner. */
    private fun marked(): Mat = Mat(2, 3, CvType.CV_8UC1, Scalar(0.0)).apply { put(0, 2, 255.0) }

    @Test
    fun `left turns the top-right corner to the top-left`() {
        val out = Rotation.LEFT.apply(marked())
        assertEquals(2, out.cols()); assertEquals(3, out.rows())
        assertEquals(255.0, out.get(0, 0)[0])
    }

    @Test
    fun `right turns the top-right corner to the bottom-right`() {
        val out = Rotation.RIGHT.apply(marked())
        assertEquals(255.0, out.get(2, 1)[0])
    }

    @Test
    fun `half turn and none`() {
        assertEquals(255.0, Rotation.HALF.apply(marked()).get(1, 0)[0])
        assertEquals(255.0, Rotation.NONE.apply(marked()).get(0, 2)[0])
    }

    @Test
    fun `names on the command line`() {
        assertEquals(Rotation.LEFT, Rotation.fromCli("Left"))
        assertNull(Rotation.fromCli("sideways"))
    }
}
