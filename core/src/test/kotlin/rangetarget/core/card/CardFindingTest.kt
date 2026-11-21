package rangetarget.core.card

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.opencv.core.Mat
import rangetarget.core.OpenCvTestSupport
import rangetarget.core.SyntheticCard
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import rangetarget.core.image.Plane
import kotlin.math.hypot

/** Stages 1–3 of card finding, on a synthetic photo whose true placement is known. */
class CardFindingTest {
    companion object {
        lateinit var photo: Mat
        val truth = SyntheticCard.camera(turnDeg = 2.0)

        @JvmStatic @BeforeAll fun setUp() {
            OpenCvTestSupport.load()
            photo = SyntheticCard.photo(SyntheticCard.flat(SyntheticCard.scatteredHoles()), truth)
        }

        /** Largest distance in photo pixels converted to mm between two maps, over the card. */
        fun worstMissMm(a: CardToPhoto, b: CardToPhoto): Double {
            var worst = 0.0
            for (x in -250..250 step 25) for (y in -250..250 step 25) {
                val p = PointMm(x.toDouble(), y.toDouble())
                val pa = a.toPx(p); val pb = b.toPx(p)
                worst = maxOf(worst, hypot(pa.x - pb.x, pa.y - pb.y) / b.pxPerMmAt(p))
            }
            return worst
        }
    }

    @Test
    fun `black disc gives centre within a few pixels and scale within 2 percent`() {
        val r = BlackDiscFinder.find(photo)
        assertNotNull(r); r!!
        val c = truth.toPx(PointMm.ORIGIN)
        // The ellipse centre is not exactly the projected centre under tilt, but close.
        assertTrue(hypot(r.ellipse.cx - c.x, r.ellipse.cy - c.y) < 4.0, "centre off: ${r.ellipse}")
        val scale = (r.ellipse.width + r.ellipse.height) / 2 / CardGeometry.blackDiameterMm
        assertEquals(truth.pxPerMmAt(), scale, 0.02 * truth.pxPerMmAt())
    }

    @Test
    fun `ring fit lands the ideal rings on the printed ones`() {
        val disc = BlackDiscFinder.find(photo)!!
        val fit = RingFitter.fit(Plane.gray(photo), disc.ellipse.toHomography(CardGeometry.blackRadiusMm))
        assertTrue(fit.inlierCount > 1500, "only ${fit.inlierCount} ring points ${fit.perRing} of ${fit.samples.size}")
        assertTrue(fit.p95ErrorMm < 0.5, "95% miss ${fit.p95ErrorMm} mm")
        // Rings can't tell rotation, so compare ring radii only: every ring point must map near its ring.
        for (ring in CardGeometry.rings) for (k in 0 until 36) {
            val a = Math.toRadians(k * 10.0 + 3)
            val r = CardGeometry.ringRadiusMm(ring)
            val px = fit.map.toPx(PointMm(r * kotlin.math.cos(a), r * kotlin.math.sin(a)))
            assertEquals(r, truth.toMm(px).radius, 0.4, "ring $ring at ${k * 10} deg")
        }
    }

    @Test
    fun `paper edges recover the turn and the paper size`() {
        val found = CardFinder.find(photo)!!
        assertEquals(4, found.fit.paperEdgesFound.size)
        assertEquals(CardGeometry.PAPER_WIDTH_MM, found.fit.paperWidthMm!!, 1.5)
        assertEquals(CardGeometry.PAPER_HEIGHT_MM, found.fit.paperHeightMm!!, 1.5)
        // With the turn fixed, the whole map matches the truth.
        assertTrue(worstMissMm(found.fit.cardToPhoto, truth) < 0.8, "map off by ${worstMissMm(found.fit.cardToPhoto, truth)} mm")
        assertTrue(found.fit.flags.isEmpty(), "unexpected flags ${found.fit.flags}")
    }

    @Test
    fun `no black disc means no card, not a guess`() {
        val blank = Mat(800, 800, org.opencv.core.CvType.CV_8UC3, SyntheticCard.BEIGE)
        assertEquals(null, CardFinder.find(blank))
    }

    @Test
    fun `flattening puts a card point at the expected pixel`() {
        val view = TopDown.warp(photo, truth, 4.0, -290.0, 290.0, -275.0, 275.0)
        assertEquals(2320, view.image.cols()); assertEquals(2200, view.image.rows())
        val p = view.toPx(PointMm(0.0, 0.0))
        assertEquals(1160.0, p.x, 1e-9); assertEquals(1100.0, p.y, 1e-9)
        // The centre of the black is black in the flat view, the paper at 150 mm is beige.
        val g = Plane.gray(view.image)
        assertTrue(g[1160, 1100] < 60)
        assertTrue(g[(1160 + 150 * 4.0 + 10).toInt(), 1100 + 40] > 150)
    }
}
