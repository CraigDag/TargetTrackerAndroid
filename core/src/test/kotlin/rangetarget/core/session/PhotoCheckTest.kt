package rangetarget.core.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.SyntheticCard
import rangetarget.core.flags.FlagCode

/** The photo check on a synthetic card, clean and spoiled in known ways. */
class PhotoCheckTest {
    companion object {
        lateinit var clean: Mat
        val truth = SyntheticCard.camera(turnDeg = 2.0)

        @JvmStatic @BeforeAll fun setUp() {
            OpenCvTestSupport.load()
            clean = SyntheticCard.photo(SyntheticCard.flat(SyntheticCard.scatteredHoles()), truth)
        }

        fun check(photo: Mat): Pair<PhotoQuality, Set<FlagCode>> {
            val box = ViewBox.DEFAULT
            val zones = CardZones(box, CardZones.nominalPaper(), CardZones.NOMINAL_EDGE_MM)
            val view = box.warp(photo, truth)
            val q = PhotoCheck.measure(photo, truth, view.image, zones)
            view.image.release(); zones.release()
            return q to PhotoCheck.flags(q).map { it.code }.toSet()
        }
    }

    @Test fun cleanPhotoPasses() {
        val (q, flags) = check(clean)
        assertTrue(q.edgeBlurMm < 0.6, "blur ${q.edgeBlurMm}")
        assertTrue(q.tiltRatio < 1.2, "tilt ${q.tiltRatio}")
        assertTrue(q.evenLight > 0.85, "light ${q.evenLight}")
        assertEquals(0.0, q.glare, 0.001)
        // The synthetic card is small in its photo (2.7 px/mm), so only that may be flagged.
        assertTrue((flags - FlagCode.PHOTO_TOO_FAR).isEmpty(), flags.toString())
    }

    @Test fun blurIsFlagged() {
        val b = Mat(); Imgproc.GaussianBlur(clean, b, Size(0.0, 0.0), 3.0)
        val (q, flags) = check(b)
        assertTrue(FlagCode.PHOTO_BLURRY in flags, "blur ${q.edgeBlurMm}")
    }

    @Test fun shadowIsFlagged() {
        val d = clean.clone()
        val half = d.submat(0, d.rows(), 0, d.cols() / 2)
        half.convertTo(half, -1, 0.45, 0.0)
        val (q, flags) = check(d)
        assertTrue(FlagCode.PHOTO_UNEVEN_LIGHT in flags, "light ${q.evenLight}")
    }

    @Test fun glareIsFlagged() {
        val g = clean.clone()
        val c = truth.toPx(rangetarget.core.geometry.PointMm(150.0, 150.0))
        Imgproc.circle(g, Point(c.x, c.y), 120, Scalar(255.0, 255.0, 255.0), -1)
        val (q, flags) = check(g)
        assertTrue(FlagCode.PHOTO_GLARE in flags, "glare ${q.glare}")
    }

    @Test fun tooManyHoles() {
        assertNull(PhotoCheck.tooManyHoles(22, 20))
        assertNotNull(PhotoCheck.tooManyHoles(60, 20))
        assertNull(PhotoCheck.tooManyHoles(60, null))
    }
}
