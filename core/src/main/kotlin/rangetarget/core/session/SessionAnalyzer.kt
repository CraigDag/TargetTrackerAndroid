package rangetarget.core.session

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import rangetarget.core.RangeTarget
import rangetarget.core.card.CardFinder
import rangetarget.core.card.CardFit
import rangetarget.core.card.RingFitter
import rangetarget.core.debug.DebugSink
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import rangetarget.core.holes.HoleDetector
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The session pipeline (docs/SPEC.md, Image processing), one photo at a time,
 * the way the app will use it: [baseline] first, then [round] for each mark
 * round's photo. Each round is compared with the last photo that worked.
 *
 * Debug images go to [debug] named `rNN_<step>`, so the person can see what
 * each step saw; the final annotated picture of a round is `rNN_4_result`.
 */
class SessionAnalyzer(private val plan: SessionPlan?, private val debug: DebugSink = DebugSink.NONE) {
    private val box = ViewBox.DEFAULT
    private var zones: CardZones? = null
    private var light: Lighting.Reference? = null
    private var prevGray: Mat? = null
    private var prevShown: Mat? = null
    private var prevLab: LabView? = null
    private var oldHoles: Mat? = null
    private val olderHoles = ArrayList<PointMm>()
    private var aims: List<AimPointReading> = emptyList()
    private var baselineReading: BaselineReading? = null
    private val rounds = ArrayList<RoundResult>()

    val aimPoints: List<AimPointReading> get() = aims

    fun baseline(photo: Mat, name: String): BaselineReading {
        val flags = ArrayList<Flag>()
        val fit = findCard(photo, "r00")
        if (fit == null) {
            flags += Flag(FlagCode.CARD_NOT_FOUND, "Couldn't find the card in the baseline photo; retake it")
            return BaselineReading(name, null, emptyList(), 0, flags).also { baselineReading = it }
        }
        flags += fit.flags
        val outline = plausibleOutline(fit)
        if (outline == null) flags += Flag(FlagCode.PAPER_EDGE_MISSING, "Paper edges didn't measure up to the card's size; using the nominal paper outline")
        val z = if (outline != null) CardZones(box, outline) else CardZones(box, CardZones.nominalPaper(), CardZones.NOMINAL_EDGE_MM)
        zones = z
        val view = box.warp(photo, fit.cardToPhoto)
        val ref = Lighting.reference(view.image, z).also { light = it }
        val norm = Lighting.normalise(view.image, z, ref)
        val lab = LabView.of(norm)
        val highest = plan?.rounds?.flatMap { r -> r.groups.mapNotNull { it.aimPointNumber } }?.maxOrNull()
        aims = AimPointFinder.find(norm, lab, z, highest)
        aims.forEach { flags += it.flags }

        // Holes already in the card (there shouldn't be any on a fresh card).
        val det = HoleDetector.detect(view, outline ?: CardZones.nominalPaper())
        for (a in aims) Imgproc.circle(det.holeMask, box.toPx(a.centreMm), ((a.diameterMm / 2 + 2) * box.pxPerMm).roundToInt(), Scalar(0.0), -1)
        val existing = det.holes.count { h -> aims.none { a -> (h.centreMm - a.centreMm).radius < a.diameterMm / 2 + 2 } }
        olderHoles += det.holes.map { it.centreMm }.filter { h -> aims.none { a -> (h - a.centreMm).radius < a.diameterMm / 2 + 2 } }
        oldHoles = det.holeMask
        det.rawMask.release()

        prevGray = FineAligner.grayBlur(view.image)
        prevShown = ChangeDetector.shownMask(view.image, box.pxPerMm)
        prevLab = lab
        debug.save("r00_2_aim_points", SessionDrawing.aimPoints(view.image, box, aims, existing))
        norm.release(); view.image.release()
        return BaselineReading(name, summary(fit), aims, existing, flags).also { baselineReading = it }
    }

    fun round(photo: Mat, name: String, roundNumber: Int): RoundResult {
        val tag = "r%02d".format(roundNumber)
        val rp = plan?.round(roundNumber)
        val distance = rp?.distanceM ?: RoundPlan.DEFAULT_DISTANCE_M
        val flags = ArrayList<Flag>()
        if (rp == null) flags += Flag(FlagCode.NO_PLAN, "No plan for this round: distance taken as %.0f m and shot counts not checked".format(distance))
        val z = zones; val pg = prevGray; val pl = prevLab; val ref = light; val old = oldHoles
        val fit = if (z != null) findCard(photo, tag) else null
        if (fit == null || z == null || pg == null || pl == null || ref == null || old == null) {
            flags += Flag(FlagCode.CARD_NOT_FOUND, "Couldn't find the card in this photo (or there's no usable baseline). The next photo will be compared with the last good one, so its holes count as one combined round.")
            return RoundResult(roundNumber, name, distance, rp?.holeDiameterMm ?: 0.0, "unknown", fit?.let { summary(it) }, null, emptyList(), emptyList(), emptyList(), flags)
                .also { rounds += it }
        }
        flags += fit.flags

        // 3. Align to the previous photo.
        val al = FineAligner.align(photo, fit.cardToPhoto, pg, z.paper, box)
        if (al.summary.leftoverP95Mm > 0.6 || al.summary.patchesUsed < 40) flags += Flag(FlagCode.ALIGNMENT_POOR,
            "This photo lines up with the previous one only to about %.1f mm (from %d patches); new holes may be missed or doubled".format(al.summary.leftoverP95Mm, al.summary.patchesUsed))
        debug.save("${tag}_1_alignment", SessionDrawing.alignment(al.view.image, box, al.patches, al.summary))

        // 4. Diff.
        val view = al.view
        val norm = Lighting.normalise(view.image, z, ref)
        val lab = LabView.of(norm)
        val smallest = rp?.holeDiameterMm ?: 5.6
        val shown = ChangeDetector.shownMask(view.image, box.pxPerMm)
        val usable = Mat(); Core.bitwise_and(shown, prevShown ?: shown, usable)
        val changes = ChangeDetector.detect(pl, lab, z, usable, smallest)
        val offCard = Mat(); Core.bitwise_and(usable, z.paper, offCard); Core.bitwise_not(offCard, offCard)
        usable.release()
        val measured = HoleFitter.measureVisibleDiameterMm(changes.holes, old, box)
        val visible = measured ?: rp?.holeDiameterMm?.plus(RIM_MM) ?: 10.0
        val (holeD, holeSource) = when {
            rp?.holeDiameterMm != null -> rp.holeDiameterMm to "plan"
            measured != null -> (measured - RIM_MM) to "measured from this round's clean holes"
            else -> 9.0 to "assumed (no plan, too few clean holes to measure)"
        }
        val allHoles = Mat(); Core.bitwise_or(old, changes.holes, allHoles)
        val ink = InkReader.read(changes.ink, lab, norm, box, aims.map { it.centreMm to it.diameterMm }, allHoles, offCard, pl)
        allHoles.release(); offCard.release()
        debug.save("${tag}_2_changes", SessionDrawing.changes(view.image, box, changes, ink))

        // 5–6. Holes, assignment, counts.
        val fitter = HoleFitter(changes.holes, old, changes.ink, box, visible)
        val out = Assigner(roundNumber, box, aims, rp, holeD, olderHoles.toList()).assign(fitter, ink)
        flags += out.flags
        val result = RoundResult(roundNumber, name, distance, holeD, holeSource, summary(fit), al.summary, ink.marks, out.shots, out.groups, flags)
        debug.save("${tag}_3_result", SessionDrawing.result(view.image, box, result, aims, ink))

        // Carry forward: this photo becomes the one the next round is compared with.
        Core.bitwise_or(old, changes.holes, old)
        olderHoles += out.shots.map { it.centreMm }
        pg.release(); prevShown?.release()
        prevGray = FineAligner.grayBlur(view.image)
        prevShown = shown
        prevLab = lab
        ink.release(); changes.release(); norm.release(); view.image.release()
        rounds += result
        return result
    }

    fun result(): SessionResult = SessionResult(
        RangeTarget.VERSION, plan?.source ?: "none",
        baselineReading ?: BaselineReading("", null, emptyList(), 0, listOf(Flag(FlagCode.CARD_NOT_FOUND, "No baseline photo"))),
        rounds.toList(), Metrics.session(rounds),
    )

    fun release() {
        prevGray?.release(); prevShown?.release(); oldHoles?.release(); zones?.release()
        prevGray = null; oldHoles = null
    }

    private fun findCard(photo: Mat, tag: String): CardFit? {
        val found = CardFinder.find(photo) ?: return null
        found.edges.view.image.release(); found.edges.paperMask.release()
        debug.save("${tag}_0_card", RingFitter.drawDebug(photo, found.rings))
        return found.fit
    }

    /** The traced paper outline, if the paper measured about the right size. */
    private fun plausibleOutline(fit: CardFit): List<PointMm>? {
        val w = fit.paperWidthMm; val h = fit.paperHeightMm
        if (w == null || h == null || fit.paperOutlineMm == null) return null
        if (abs(w - CardGeometry.PAPER_WIDTH_MM) > 15 || abs(h - CardGeometry.PAPER_HEIGHT_MM) > 15) return null
        return fit.paperOutlineMm
    }

    private fun summary(fit: CardFit) = CardSummary(fit.pxPerMmAtCentre, fit.ringMissMedianMm, fit.ringMissP95Mm, fit.turnCorrectionDeg, fit.largestBendMm)

    companion object {
        /** A hole shows about this much bigger than the bullet in a photo (torn, greyish rim). */
        const val RIM_MM = 1.0
    }
}

