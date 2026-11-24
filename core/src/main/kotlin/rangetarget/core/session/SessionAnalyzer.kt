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
import java.io.File
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.RangeJson
import kotlin.math.sqrt
import org.opencv.core.Size
import kotlin.math.roundToInt

/**
 * The session pipeline (docs/SPEC.md, Image processing), one photo at a time,
 * the way the app will use it: [baseline] first, then [round] for each mark
 * round's photo. Each round is compared with the last photo that worked.
 *
 * Debug images go to [debug] named `rNN_<step>`, so the person can see what
 * each step saw; the final annotated picture of a round is `rNN_4_result`.
 * [views] gets a clean copy of each photo's top-down view, named `rNN`
 * (`r00` for the baseline), in [ViewBox.DEFAULT]: the phone app shows it on
 * the screen where groups are circled.
 */
/** What [SessionAnalyzer.restore] reads back (beside two PNG files). */
@kotlinx.serialization.Serializable
data class AnalyzerState(
    val version: Int,
    val photosRead: Int,
    val viewFile: String,
    val holesFile: String,
    val paperOutlineMm: List<PointMm>,
    val edgeMm: Double,
    val beige: List<Double>,
    val black: List<Double>,
    val olderHoles: List<PointMm>,
    val aims: List<AimPointReading>,
    val prevBlurMm: Double?,
)

class SessionAnalyzer(
    private val plan: SessionPlan?,
    private val debug: DebugSink = DebugSink.NONE,
    private val views: DebugSink = DebugSink.NONE,
) {
    private val box = ViewBox.DEFAULT
    private var zones: CardZones? = null
    private var light: Lighting.Reference? = null
    private var prevGray: Mat? = null
    private var prevShown: Mat? = null
    private var prevLab: LabView? = null
    /** The previous photo's lighting-normalised view and how blurred it was (photo check), for matching sharpness. */
    private var prevNorm: Mat? = null
    private var prevBlurMm = Double.NaN
    private var oldHoles: Mat? = null
    private val olderHoles = ArrayList<PointMm>()
    private var aims: List<AimPointReading> = emptyList()
    private var baselineReading: BaselineReading? = null
    private val rounds = ArrayList<RoundResult>()
    private var zonesOutline: List<PointMm> = emptyList()
    private var zonesEdgeMm = CardZones.EDGE_MM

    /** The last photo read successfully: 0 for the baseline, then the round number; -1 before the baseline. */
    var photosRead = -1
        private set

    /**
     * If set, everything needed to carry on is saved here after each photo, so a
     * session can continue with [restore] after the app was closed instead of
     * reading every earlier photo again.
     */
    var stateDir: File? = null

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
        zonesOutline = outline ?: CardZones.nominalPaper()
        zonesEdgeMm = if (outline != null) CardZones.EDGE_MM else CardZones.NOMINAL_EDGE_MM
        val z = CardZones(box, zonesOutline, zonesEdgeMm)
        zones = z
        val view = box.warp(photo, fit.cardToPhoto)
        val quality = PhotoCheck.measure(photo, fit.cardToPhoto, view.image, z)
        flags += PhotoCheck.flags(quality)
        views.save("r00", view.image.clone())
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
        prevNorm = norm; prevBlurMm = quality.edgeBlurMm
        debug.save("r00_2_aim_points", SessionDrawing.aimPoints(view.image, box, aims, existing))
        photosRead = 0
        saveState(view.image)
        view.image.release()
        return BaselineReading(name, summary(fit), aims, existing, flags, quality).also { baselineReading = it }
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
        val al = FineAligner.align(photo, fit.cardToPhoto, pg, z.paper, box, detailSpots())
        if (al.summary.leftoverP95Mm > 0.6 || al.summary.patchesUsed < 40) flags += Flag(FlagCode.ALIGNMENT_POOR,
            "This photo lines up with the previous one only to about %.1f mm (from %d patches); new holes may be missed or doubled".format(al.summary.leftoverP95Mm, al.summary.patchesUsed))
        val quality = PhotoCheck.measure(photo, fit.cardToPhoto, al.view.image, z)
        flags += PhotoCheck.flags(quality)
        views.save(tag, al.view.image.clone())
        debug.save("${tag}_1_alignment", SessionDrawing.alignment(al.view.image, box, al.patches, al.summary))

        // 4. Diff.
        val view = al.view
        val norm = Lighting.normalise(view.image, z, ref)
        val lab = LabView.of(norm)
        val smallest = rp?.holeDiameterMm ?: 5.6
        val shown = ChangeDetector.shownMask(view.image, box.pxPerMm)
        val usable = Mat(); Core.bitwise_and(shown, prevShown ?: shown, usable)
        // A sharp photo compared with a blurry one shows the printed lines and numbers as
        // changes (darker and thinner), which read as holes. Blur the sharper of the two
        // to match the other before comparing.
        val (labPrev, labCur) = matchSharpness(pl, lab, prevNorm, norm, prevBlurMm, quality.edgeBlurMm)
        val changes = ChangeDetector.detect(labPrev, labCur, z, usable, smallest)
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
        val ink = InkReader.read(changes.ink, lab, norm, box, aims.map { it.centreMm to it.diameterMm }, allHoles, offCard, labPrev)
        allHoles.release(); offCard.release()
        debug.save("${tag}_2_changes", SessionDrawing.changes(view.image, box, changes, ink))

        // 5–6. Holes, assignment, counts.
        val fitter = HoleFitter(changes.holes, old, changes.ink, box, visible)
        val out = Assigner(roundNumber, box, aims, rp, holeD, olderHoles.toList()).assign(fitter, ink)
        flags += out.flags
        val result = RoundResult(roundNumber, name, distance, holeD, holeSource, summary(fit), al.summary, ink.marks, out.shots, out.groups, flags, quality)
        debug.save("${tag}_3_result", SessionDrawing.result(view.image, box, result, aims, ink))

        // Carry forward: this photo becomes the one the next round is compared with.
        Core.bitwise_or(old, changes.holes, old)
        olderHoles += out.shots.map { it.centreMm }
        pg.release(); prevShown?.release()
        prevGray = FineAligner.grayBlur(view.image)
        prevShown = shown
        prevLab = lab
        prevNorm?.release(); prevNorm = norm; prevBlurMm = quality.edgeBlurMm
        photosRead = roundNumber
        saveState(view.image)
        ink.release(); changes.release(); view.image.release()
        rounds += result
        return result
    }

    fun result(): SessionResult = SessionResult(
        RangeTarget.VERSION, plan?.source ?: "none",
        baselineReading ?: BaselineReading("", null, emptyList(), 0, listOf(Flag(FlagCode.CARD_NOT_FOUND, "No baseline photo"))),
        rounds.toList(), Metrics.session(rounds),
    )

    fun release() {
        prevGray?.release(); prevShown?.release(); oldHoles?.release(); zones?.release(); prevNorm?.release(); prevNorm = null
        prevGray = null; oldHoles = null
    }

    private fun findCard(photo: Mat, tag: String): CardFit? {
        val found = CardFinder.find(photo) ?: return null
        found.edges.view.image.release(); found.edges.paperMask.release()
        debug.save("${tag}_0_card", RingFitter.drawDebug(photo, found.rings))
        return found.fit
    }

    /**
     * Places with detail in both directions, for extra alignment patches: the
     * printed ring numbers on the four axes. Ring lines alone can't show the card
     * sliding along them. (Aim points are left out: shots land on them, which
     * changes the patch.)
     */
    private fun detailSpots(): List<PointMm> = buildList {
        for (n in 1..8) {
            val r = (CardGeometry.ringRadiusMm(n) + CardGeometry.ringRadiusMm(n + 1)) / 2
            add(PointMm(r, 0.0)); add(PointMm(-r, 0.0)); add(PointMm(0.0, r)); add(PointMm(0.0, -r))
        }
    }

    /** Writes what [restore] needs: the last photo's flattened view, the holes so far and a small JSON file. */
    private fun saveState(view: Mat) {
        val dir = stateDir ?: return
        val z = zones ?: return; val ref = light ?: return; val old = oldHoles ?: return
        runCatching {
            dir.mkdirs()
            val tag = "%02d".format(photosRead)
            val viewFile = "view_$tag.png"; val holesFile = "holes_$tag.png"
            if (!Imgcodecs.imwrite(File(dir, viewFile).path, view) || !Imgcodecs.imwrite(File(dir, holesFile).path, old)) return
            val st = AnalyzerState(STATE_VERSION, photosRead, viewFile, holesFile, zonesOutline, zonesEdgeMm,
                ref.beige.toList(), ref.black.toList(), olderHoles.toList(), aims, prevBlurMm.takeIf { it.isFinite() })
            val tmp = File(dir, "state.json.tmp")
            tmp.writeText(RangeJson.encodeToString(AnalyzerState.serializer(), st))
            val target = File(dir, "state.json")
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            // Earlier photos' files are no longer needed.
            dir.listFiles()?.filter { f -> (f.name.startsWith("view_") || f.name.startsWith("holes_")) && f.name != viewFile && f.name != holesFile }?.forEach { it.delete() }
        }
    }

    /**
     * Lab views of the previous and current photo with the sharper one blurred to match
     * the other, from the black-disc edge blur the photo check measured. Unchanged when
     * either blur is unknown or they're close.
     */
    private fun matchSharpness(prevLab: LabView, curLab: LabView, prevNorm: Mat?, curNorm: Mat, prevBlur: Double, curBlur: Double): Pair<LabView, LabView> {
        if (prevNorm == null || prevBlur.isNaN() || curBlur.isNaN()) return prevLab to curLab
        // The 25–75% rise of a blurred edge spans 1.35 standard deviations of the blur.
        val sp = prevBlur / 1.35; val sc = curBlur / 1.35
        val extraPx = sqrt(abs(sp * sp - sc * sc)) * box.pxPerMm
        if (extraPx < MATCH_MIN_PX) return prevLab to curLab
        val src = if (sc < sp) curNorm else prevNorm
        val b = Mat(); Imgproc.GaussianBlur(src, b, Size(0.0, 0.0), extraPx)
        val lab = LabView.of(b); b.release()
        return if (sc < sp) prevLab to lab else lab to curLab
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
        const val STATE_VERSION = 1

        /**
         * An analyzer that carries on from what [stateDir] saved, as if it had just read
         * photo [AnalyzerState.photosRead] itself; null if nothing usable is saved.
         */
        fun restore(stateDir: File, plan: SessionPlan?, debug: DebugSink = DebugSink.NONE, views: DebugSink = DebugSink.NONE): SessionAnalyzer? {
            val st = runCatching { RangeJson.decodeFromString(AnalyzerState.serializer(), File(stateDir, "state.json").readText()) }.getOrNull() ?: return null
            if (st.version != STATE_VERSION) return null
            val view = Imgcodecs.imread(File(stateDir, st.viewFile).path, Imgcodecs.IMREAD_COLOR)
            val holes = Imgcodecs.imread(File(stateDir, st.holesFile).path, Imgcodecs.IMREAD_GRAYSCALE)
            val a = SessionAnalyzer(plan, debug, views)
            if (view.empty() || holes.empty() || view.cols() != a.box.widthPx || view.rows() != a.box.heightPx) { view.release(); holes.release(); return null }
            val z = CardZones(a.box, st.paperOutlineMm, st.edgeMm)
            val ref = Lighting.Reference(st.beige.toDoubleArray(), st.black.toDoubleArray())
            a.zonesOutline = st.paperOutlineMm; a.zonesEdgeMm = st.edgeMm
            a.zones = z; a.light = ref; a.oldHoles = holes
            a.olderHoles += st.olderHoles; a.aims = st.aims
            a.prevGray = FineAligner.grayBlur(view)
            a.prevShown = ChangeDetector.shownMask(view, a.box.pxPerMm)
            val norm = Lighting.normalise(view, z, ref)
            a.prevLab = LabView.of(norm); a.prevNorm = norm
            a.prevBlurMm = st.prevBlurMm ?: Double.NaN
            a.photosRead = st.photosRead
            a.stateDir = stateDir
            view.release()
            return a
        }

        /** A hole shows about this much bigger than the bullet in a photo (torn, greyish rim). */
        const val RIM_MM = 1.0
        /** Smallest blur difference (as extra blur, pixels of the flattened view) worth matching. */
        const val MATCH_MIN_PX = 0.5
    }
}

