package rangetarget.core.session

import org.opencv.core.Mat
import rangetarget.core.card.CardFinder
import rangetarget.core.flags.Flag
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.holes.HoleDetector
import rangetarget.core.holes.StickerFinder

/**
 * A single photo with nothing to compare against (Markup, "from device"):
 * every hole on the card is a candidate, and the shooter circles only the
 * groups to measure. Uses the same top-down view as [SessionAnalyzer].
 */
object LonePhoto {
    class Result(
        /** Top-down view of the card ([ViewBox.DEFAULT]); the caller releases it. */
        val view: Mat,
        val shots: List<ShotReading>,
        val flags: List<Flag>,
    )

    /** [holeDiameterMm] is used for scoring (a 9 mm hole if the calibre isn't known). */
    fun read(photo: Mat, holeDiameterMm: Double = 9.0, round: Int = 1): Result? {
        val found = CardFinder.find(photo) ?: return null
        found.edges.view.image.release(); found.edges.paperMask.release()
        val fit = found.fit
        val box = ViewBox.DEFAULT
        val view = box.warp(photo, fit.cardToPhoto)
        val outline = fit.paperOutlineMm ?: CardZones.nominalPaper()
        val det = HoleDetector.detect(view, outline, StickerFinder.find(view))
        det.rawMask.release(); det.holeMask.release()
        val flags = fit.flags.toMutableList()
        det.clusters.forEach { flags += Flag(rangetarget.core.flags.FlagCode.UNRESOLVABLE_CLUSTER, it.reason, it.centreMm) }
        val shots = det.holes.sortedWith(compareByDescending<rangetarget.core.holes.Hole> { it.centreMm.y }.thenBy { it.centreMm.x })
            .mapIndexed { i, h ->
                ShotReading(
                    id = "r%ds%02d".format(round, i + 1), round = round, groupId = null, centreMm = h.centreMm, fromAimMm = null,
                    diameterMm = holeDiameterMm, score = CardGeometry.scoreByEdge(h.centreMm, holeDiameterMm),
                    source = if (h.blobId == null) ShotSource.CLEAN else ShotSource.MERGED,
                    confidence = if (h.confident) 1.0 else 0.5, flags = h.flags,
                )
            }
        return Result(view.image, shots, flags)
    }
}
