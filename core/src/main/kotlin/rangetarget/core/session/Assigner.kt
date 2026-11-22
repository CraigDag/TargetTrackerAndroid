package rangetarget.core.session

import org.opencv.core.Mat
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Gives each new hole to the new loop drawn round it (docs/SPEC.md, Image
 * processing steps 5–6), cross-checks loops against aim points, checks counts
 * against shots fired, and raises a flag for every case in the Edge cases
 * table instead of guessing.
 */
class Assigner(
    private val round: Int,
    private val box: ViewBox,
    private val aimPoints: List<AimPointReading>,
    private val plan: RoundPlan?,
    private val holeDiameterMm: Double,
    /** Every hole on the card before this round (for "went straight through an old hole" suggestions). */
    private val olderHoles: List<PointMm>,
) {
    class Output(val shots: List<ShotReading>, val groups: List<GroupReading>, val flags: List<Flag>)

    private class LoopInfo(val mark: InkMark, val region: Mat, val aim: AimPointReading?)

    fun assign(fitter: HoleFitter, ink: InkReader.Read): Output {
        val roundFlags = ArrayList<Flag>()
        val loops = linkAimPoints(ink, roundFlags)

        // Stray ink and loop problems.
        for (m in ink.marks) when (m.kind) {
            InkKind.STRAY -> roundFlags += Flag(FlagCode.STRAY_INK, "New ${pretty(m.colourName)} ink that isn't a closed loop (ignored; tap to make it a loop)", m.centreMm)
            InkKind.LOOP_WITH_GAP -> roundFlags += Flag(FlagCode.LOOP_NOT_CLOSED, "The ${pretty(m.colourName)} loop has a small gap; treated as closed", m.centreMm)
            InkKind.LOOP_AT_EDGE -> roundFlags += Flag(FlagCode.LOOP_ON_PAPER_EDGE, "The ${pretty(m.colourName)} loop runs to the paper edge; the edge was used to close it. Check its holes.", m.centreMm)
            InkKind.LOOP -> {}
        }
        loops.groupBy { it.mark.colourName }.filter { it.value.size > 1 }.forEach { (c, ls) ->
            roundFlags += Flag(FlagCode.COLOUR_USED_TWICE, "${pretty(c).replaceFirstChar { it.uppercase() }} used for ${ls.size} loops this round; check each group is right", ls.first().mark.centreMm)
        }

        // Plan groups matched to loops: by aim point number, then by colour.
        val planFor = HashMap<LoopInfo, GroupPlan>()
        val planGroups = plan?.groups.orEmpty().toMutableList()
        for (l in loops) planGroups.firstOrNull { it.aimPointNumber != null && it.aimPointNumber == l.aim?.number }?.let { planFor[l] = it; planGroups.remove(it) }
        for (l in loops) if (l !in planFor) planGroups.firstOrNull { it.aimPointNumber == null && it.colour != null && it.colour == l.mark.colourName }?.let { planFor[l] = it; planGroups.remove(it) }
        if (plan != null && planGroups.isNotEmpty()) roundFlags += Flag(FlagCode.FEWER_HOLES_THAN_FIRED,
            "The plan has ${planGroups.size} group(s) with no loop found: ${planGroups.joinToString { g -> g.aimPointNumber?.let { "aim point $it" } ?: (g.colour ?: "unnamed") }}")

        // Assign, then split merged blobs where a group is short of holes (or join
        // circles where it has too many), and assign again.
        val split = HashSet<Int>()
        val triedSplit = HashSet<Int>()
        var holes = fitter.fitAll()
        var tries = 0
        while (true) {
            val byLoop = assignHoles(holes, loops)
            val over = loops.filter { l ->
                val fired = planFor[l]?.let { it.firedShots ?: it.expectedShots } ?: return@filter false
                (byLoop[l]?.size ?: 0) > fired
            }
            if (over.isEmpty() || tries++ > 12) break
            val blobsIn = over.flatMap { l -> byLoop[l].orEmpty().map { it.blob } }.toSet()
            val pick = fitter.mergeCandidates().firstOrNull { (blob, alone) -> blob in blobsIn && alone < MAX_MERGE_ALONE }?.first ?: break
            fitter.removeHole(pick); split += pick
            holes = fitter.holes()
        }
        // A group short of holes is made up by splitting a merged hole only when the extra
        // circle clearly explains missing paper on its own (MIN_SPLIT_ALONE): validation
        // (Phase 4) showed that a weaker split lands in the wrong place about half the time,
        // because the missing shot is usually hidden in a hole that looks single. Otherwise
        // the shortfall is flagged, with the likeliest spots to check.
        tries = 0
        while (true) {
            val byLoop = assignHoles(holes, loops)
            val short = loops.filter { l ->
                val fired = planFor[l]?.let { it.firedShots ?: it.expectedShots } ?: return@filter false
                (byLoop[l]?.size ?: 0) < fired
            }
            if (short.isEmpty() || tries++ > 12) break
            val blobsIn = short.flatMap { l -> byLoop[l].orEmpty().map { it.blob } }.toSet()
            val cands = fitter.splitCandidates().filter { (blob, excess) -> blob in blobsIn && blob !in triedSplit && excess >= MIN_SPLIT_EXCESS }
            val pick = cands.firstOrNull()?.first ?: break
            triedSplit += pick
            fitter.addHole(pick)
            if (fitter.weakestAlone(pick) < MIN_SPLIT_ALONE) { fitter.removeHole(pick); continue }
            split += pick
            holes = fitter.holes()
        }
        val byLoop = assignHoles(holes, loops)

        // Shots, numbered top to bottom then left to right.
        val ordered = holes.sortedWith(compareByDescending<FoundHole> { (it.centreMm.y / 10).roundToInt() }.thenBy { it.centreMm.x })
        val loopOf = HashMap<FoundHole, List<LoopInfo>>()
        for (l in loops) for (h in byLoop[l].orEmpty()) loopOf[h] = loopOf[h].orEmpty() + l
        val sortedLoops = loops.sortedWith(compareBy<LoopInfo> { it.aim?.number ?: Int.MAX_VALUE }.thenByDescending { it.mark.centreMm.y }.thenBy { it.mark.centreMm.x })
        val groupId = sortedLoops.mapIndexed { i, l -> l to "r${round}g${i + 1}" }.toMap()
        val shotFlags = HashMap<FoundHole, MutableList<FlagCode>>()
        val chosen = HashMap<FoundHole, LoopInfo?>()
        for (h in ordered) {
            val f = ArrayList<FlagCode>()
            when (h.source) { ShotSource.GROWN_HOLE -> f += FlagCode.HOLE_GREW; ShotSource.MERGED -> f += FlagCode.OVERLAPPING_HOLES; else -> {} }
            if (h.blob in split) f += FlagCode.SPLIT_TO_MATCH_COUNT
            val ls = loopOf[h].orEmpty()
            chosen[h] = when (ls.size) {
                0 -> { f += FlagCode.HOLE_OUTSIDE_LOOPS; null }
                1 -> ls[0]
                else -> { f += FlagCode.HOLE_IN_TWO_LOOPS; ls.minBy { l -> l.aim?.let { dist(it.centreMm, h.centreMm) } ?: Double.MAX_VALUE } }
            }
            shotFlags[h] = f
        }
        val shots = ordered.mapIndexed { i, h ->
            val l = chosen[h]
            val aim = l?.aim?.centreMm
            ShotReading(
                id = "r%ds%02d".format(round, i + 1), round = round, groupId = l?.let { groupId[it] },
                centreMm = h.centreMm, fromAimMm = aim?.let { h.centreMm - it }, diameterMm = holeDiameterMm,
                score = CardGeometry.scoreByEdge(h.centreMm, holeDiameterMm), source = h.source,
                confidence = h.confidence, flags = shotFlags[h].orEmpty(),
            )
        }
        val shotOf = ordered.zip(shots).toMap()
        for ((h, s) in shotOf) {
            if (FlagCode.HOLE_OUTSIDE_LOOPS in s.flags) {
                val near = loops.minByOrNull { dist(it.mark.centreMm, h.centreMm) }
                roundFlags += Flag(FlagCode.HOLE_OUTSIDE_LOOPS, "Shot ${s.id} is outside every new loop" +
                    (near?.let { "; nearest is the ${pretty(it.mark.colourName)} loop" } ?: "") + ". Assign it, tag it as a flyer, or ignore it.", h.centreMm)
            }
            if (FlagCode.HOLE_IN_TWO_LOOPS in s.flags) roundFlags += Flag(FlagCode.HOLE_IN_TWO_LOOPS,
                "Shot ${s.id} is inside ${loopOf[h]!!.size} new loops; given to ${s.groupId} (nearest aim point) for now", h.centreMm)
        }

        val distance = plan?.distanceM ?: RoundPlan.DEFAULT_DISTANCE_M
        val groups = sortedLoops.map { l ->
            val id = groupId.getValue(l)
            val mine = shots.filter { it.groupId == id }
            val gp = planFor[l]
            val fired = gp?.let { it.firedShots ?: it.expectedShots }
            val flags = ArrayList<Flag>()
            if (l.aim == null) flags += Flag(FlagCode.LOOP_WITHOUT_AIM_POINT, "No aim point near the ${pretty(l.mark.colourName)} loop; offset from aim not measured", l.mark.centreMm)
            if (fired != null && mine.size < fired) {
                val inside = olderHoles.filter { insideRegion(l.region, it) }
                val myBlobs = byLoop[l].orEmpty().map { it.blob }.toSet()
                val merged = fitter.splitCandidates().filter { (blob, excess) -> blob in myBlobs && excess >= MIN_SPLIT_EXCESS }
                    .mapNotNull { fitter.blobCentre(it.first) }
                val spots = (merged.map { "a big or odd-shaped hole at (%.0f, %.0f) mm".format(it.x, it.y) } +
                    inside.take(3).map { "an older hole at (%.0f, %.0f) mm".format(it.x, it.y) }).take(4)
                flags += Flag(FlagCode.FEWER_HOLES_THAN_FIRED, "Found ${mine.size} of $fired shots. A shot may be hidden in another hole" +
                    (if (spots.isNotEmpty()) "; likely spots: " + spots.joinToString("; ") + "." else ", or it missed the card."), l.mark.centreMm)
            } else if (fired != null && mine.size > fired) {
                flags += Flag(FlagCode.MORE_HOLES_THAN_FIRED, "Found ${mine.size} holes but $fired shots were fired: an earlier hole may have been missed, or a neighbour's shot landed here", l.mark.centreMm)
            } else if (mine.any { FlagCode.SPLIT_TO_MATCH_COUNT in it.flags }) {
                flags += Flag(FlagCode.SPLIT_TO_MATCH_COUNT, "Some holes here were merged; re-fitted to match the $fired shots fired. Check the centres.", l.mark.centreMm)
            }
            GroupReading(id, round, l.mark.id, l.mark.colourName, l.mark.colourRgb, l.aim?.number, gp?.expectedShots, gp?.firedShots,
                mine.size, mine.map { it.id }, Metrics.group(mine, l.aim?.centreMm, distance), flags)
        }
        return Output(shots, groups, roundFlags)
    }

    private fun assignHoles(holes: List<FoundHole>, loops: List<LoopInfo>): Map<LoopInfo, List<FoundHole>> =
        loops.associateWith { l -> holes.filter { insideRegion(l.region, it.centreMm) } }

    private fun insideRegion(region: Mat, p: PointMm): Boolean {
        val px = box.toPx(p)
        val x = px.x.roundToInt(); val y = px.y.roundToInt()
        if (x < 0 || y < 0 || x >= region.cols() || y >= region.rows()) return false
        return region.get(y, x)[0] > 0
    }

    /**
     * Each loop's aim point: the nearest one inside or within 60 mm of the loop.
     * A hand-drawn aim point prefers the loop of its own colour.
     */
    private fun linkAimPoints(ink: InkReader.Read, flags: MutableList<Flag>): List<LoopInfo> {
        val loops = ink.marks.filter { it.kind != InkKind.STRAY }
        val cost = { m: InkMark, a: AimPointReading ->
            val d = distToOutline(m, a.centreMm)
            val colourPenalty = if (a.style == AimPointStyle.HAND_DRAWN && a.colourName != m.colourName) 40.0 else 0.0
            d + colourPenalty
        }
        val pairs = loops.flatMap { m -> aimPoints.map { a -> Triple(m, a, cost(m, a)) } }.filter { it.third <= 60.0 }.sortedBy { it.third }
        val aimOf = HashMap<Int, AimPointReading>()
        val usedAim = HashMap<Int, Int>()
        for ((m, a, _) in pairs) {
            if (m.id in aimOf) continue
            if (a.id in usedAim) {
                flags += Flag(FlagCode.AIM_POINT_TWO_LOOPS, "Two loops are next to aim point ${a.number ?: "?"}; check which group was fired at it", a.centreMm)
                continue
            }
            aimOf[m.id] = a; usedAim[a.id] = m.id
        }
        // One loop left without an aim point and one planned aim point left without a
        // loop (a group pulled far off its aim point, e.g. at 25 m): pair them, flagged.
        val planned = plan?.groups.orEmpty().mapNotNull { it.aimPointNumber }.toSet()
        val freeLoops = loops.filter { it.id !in aimOf }
        val freeAims = aimPoints.filter { it.id !in usedAim && it.number != null && it.number in planned }
        if (freeLoops.size == 1 && freeAims.size == 1) {
            val m = freeLoops[0]; val a = freeAims[0]
            aimOf[m.id] = a
            flags += Flag(FlagCode.AIM_POINT_BY_ELIMINATION, "The ${pretty(m.colourName)} loop is far from every aim point; given to aim point ${a.number}, the only planned one left. Check it.", m.centreMm)
        }
        return loops.map { LoopInfo(it, ink.regions.getValue(it.id), aimOf[it.id]) }
    }

    /** Distance from [p] to the loop's outline, or 0 if [p] is inside it. */
    private fun distToOutline(m: InkMark, p: PointMm): Double {
        val o = m.outlineMm
        if (o.size < 3) return dist(m.centreMm, p)
        if (pointInPolygon(o, p)) return 0.0
        var best = Double.MAX_VALUE
        for (i in o.indices) {
            val a = o[i]; val b = o[(i + 1) % o.size]
            val dx = b.x - a.x; val dy = b.y - a.y
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy).coerceAtLeast(1e-9)).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(a.x + t * dx - p.x, a.y + t * dy - p.y))
        }
        return best
    }

    private fun pointInPolygon(poly: List<PointMm>, p: PointMm): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    private fun dist(a: PointMm, b: PointMm) = hypot(a.x - b.x, a.y - b.y)

    companion object {
        /** A blob with at least this much unexplained area (in holes) may hide a missing shot. */
        const val MIN_SPLIT_EXCESS = 0.4
        /** A split is kept only if every circle in the blob alone explains at least this much (in holes). */
        const val MIN_SPLIT_ALONE = 0.5
        /** A circle that alone explains less than this (in holes) may be dropped when a group has too many. */
        const val MAX_MERGE_ALONE = 0.6
        fun pretty(colourName: String) = colourName.replace('_', ' ')
    }
}
