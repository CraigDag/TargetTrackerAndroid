package rangetarget.core.card

import kotlinx.serialization.Serializable
import org.opencv.core.Mat
import rangetarget.core.debug.DebugSink
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import rangetarget.core.image.Plane

/** Where the card is in a photo, and how sure we are. Written to card.json. */
@Serializable
data class CardFit(
    /** Card mm (+y up, origin at the centre) to photo pixels. */
    val cardToPhoto: CardMap,
    /** Largest bend added on top of the flat-paper map, in mm (paper not flat, lens). */
    val largestBendMm: Double,
    val blackDisc: EllipsePx,
    /** Fraction of the black disc's edge that was clean enough to use. */
    val blackDiscEdgeUsed: Double,
    val ringPointsUsed: Int,
    val ringPointsIgnored: Int,
    /** How far the printed ring lines are from the ideal rings, after fitting. */
    val ringMissMedianMm: Double,
    val ringMissP95Mm: Double,
    val perRing: List<RingResidual>,
    val paperEdgesFound: List<Side>,
    val paperWidthMm: Double?,
    val paperHeightMm: Double?,
    /** Turn applied to make the paper edges square, in degrees anticlockwise. */
    val turnCorrectionDeg: Double,
    val paperCornersMm: List<PointMm>?,
    /** Paper outline traced along the edges (follows curl), for the hole finder. */
    val paperOutlineMm: List<PointMm>?,
    val pxPerMmAtCentre: Double,
    val flags: List<Flag>,
)

/**
 * Finds the card in an upright photo (Phase 1, step 1):
 * 1. the black disc gives a first centre and scale;
 * 2. the printed ring lines refine that into a full perspective map;
 * 3. the paper edges fix which way is up (rings can't tell).
 */
object CardFinder {
    class Result(val fit: CardFit, val rings: RingFitResult, val edges: PaperEdges)

    fun find(photo: Mat, debug: DebugSink = DebugSink.NONE): Result? {
        val flags = ArrayList<Flag>()
        val disc = BlackDiscFinder.find(photo) ?: return null
        debug.save("02_black_disc", BlackDiscFinder.drawDebug(photo, disc))
        if (disc.fitFraction < 0.5) flags += Flag(FlagCode.BLACK_DISC_DOUBTFUL,
            "Only %.0f%% of the black disc's edge looked clean".format(100 * disc.fitFraction))
        disc.mask.release()

        val gray = Plane.gray(photo)
        val start = disc.ellipse.toHomography(CardGeometry.blackRadiusMm)
        var rings = RingFitter.fit(gray, start)

        // Fix the turn from the paper edges, then fit the rings once more from there
        // (rings don't move under a turn, so this only tidies the result).
        var edges = PaperEdgeFinder.find(photo, rings.map)
        val turn = edges.turnDeg ?: 0.0
        if (edges.edges.isNotEmpty()) {
            val turned = rings.map.homography * Homography.rotationMm(turn)
            rings = RingFitter.fit(gray, turned)
            edges.paperMask.release(); edges.view.image.release()
            edges = PaperEdgeFinder.find(photo, rings.map)
        }
        debug.save("03_ring_fit", RingFitter.drawDebug(photo, rings))
        debug.save("04_paper_edges", PaperEdgeFinder.drawDebug(edges))

        val missing = Side.entries - edges.edges.keys
        if (missing.isNotEmpty()) flags += Flag(FlagCode.PAPER_EDGE_MISSING,
            "Paper edge not found: ${missing.joinToString { it.name.lowercase() }}. Card rotation rests on the others.")
        if (rings.p95ErrorMm > 1.5 || rings.inlierCount < 500) flags += Flag(FlagCode.RING_FIT_POOR,
            "Printed rings matched only to within %.1f mm (95%%) from %d points".format(rings.p95ErrorMm, rings.inlierCount))

        val h = rings.map
        val fit = CardFit(
            cardToPhoto = h,
            largestBendMm = h.largestBendMm(),
            blackDisc = disc.ellipse,
            blackDiscEdgeUsed = disc.fitFraction,
            ringPointsUsed = rings.inlierCount,
            ringPointsIgnored = rings.samples.size - rings.inlierCount,
            ringMissMedianMm = rings.medianErrorMm,
            ringMissP95Mm = rings.p95ErrorMm,
            perRing = rings.perRing,
            paperEdgesFound = edges.edges.keys.sorted(),
            paperWidthMm = edges.widthMm,
            paperHeightMm = edges.heightMm,
            turnCorrectionDeg = turn,
            paperCornersMm = edges.corners(),
            paperOutlineMm = edges.outline(),
            pxPerMmAtCentre = h.pxPerMmAt(),
            flags = flags,
        )
        return Result(fit, rings, edges)
    }
}
