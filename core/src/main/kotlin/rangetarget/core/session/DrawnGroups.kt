package rangetarget.core.session

import kotlinx.serialization.Serializable
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.PointMm
import kotlin.math.hypot

/**
 * A group loop drawn with a finger in the app (docs/SPEC.md, "Marking in the
 * app"). It is taken as exact: no colour reading, no edge or gap problems.
 */
@Serializable
data class DrawnLoop(
    /** The loop as drawn, in card mm. Closed implicitly (last point joins the first). */
    val outlineMm: List<PointMm>,
    /** Aim point the group was fired at (0 is the card centre); null if none. */
    val aimPointNumber: Int? = null,
    /** Display colour the app drew it in. */
    val colourName: String = "",
    val colourRgb: String = "#ffffff",
)

/** An aim point as the app keeps it: read from the blank-card photo, or tapped by the shooter. */
@Serializable
data class AimPoint(
    /** Shooting-order number; 0 is the card centre, which is always an aim point. */
    val number: Int,
    val centreMm: PointMm,
    /** "centre", "found" (read from the photo) or "tapped". */
    val source: String,
)

/**
 * Turns finger-drawn loops into groups: each shot goes to the loop drawn round
 * it, then the group numbers come from [Metrics]. Shots outside every loop are
 * left unassigned (never dropped), and anything doubtful is flagged.
 */
object DrawnGroups {
    class Output(val shots: List<ShotReading>, val groups: List<GroupReading>) {
        val unassigned: List<ShotReading> get() = shots.filter { it.groupId == null }
    }

    fun measure(
        round: Int,
        shots: List<ShotReading>,
        loops: List<DrawnLoop>,
        aims: List<AimPoint>,
        distanceM: Double,
        /** Shots fired per group, if the plan says. */
        expectedShots: Int? = null,
    ): Output {
        val aimAt = aims.associate { it.number to it.centreMm }
        val ids = loops.indices.map { "r%dg%d".format(round, it + 1) }
        val owner = HashMap<String, Int>()
        val inTwo = HashSet<String>()
        for (s in shots) {
            val hits = loops.indices.filter { inside(s.centreMm, loops[it].outlineMm) }
            if (hits.isEmpty()) continue
            if (hits.size > 1) inTwo += s.id
            // In two loops: the loop whose aim point is nearest wins, else the one drawn last.
            val pick = hits.minByOrNull { i -> loops[i].aimPointNumber?.let { aimAt[it] }?.let { (s.centreMm - it).radius } ?: Double.MAX_VALUE }
                ?.takeIf { i -> loops[i].aimPointNumber?.let { aimAt[it] } != null } ?: hits.last()
            owner[s.id] = pick
        }
        val outShots = shots.map { s ->
            val i = owner[s.id]
            val aim = i?.let { loops[it].aimPointNumber }?.let { aimAt[it] }
            s.copy(
                groupId = i?.let { ids[it] },
                fromAimMm = aim?.let { s.centreMm - it },
                flags = (s.flags - FlagCode.HOLE_IN_TWO_LOOPS - FlagCode.HOLE_OUTSIDE_LOOPS) + when {
                    i == null -> listOf(FlagCode.HOLE_OUTSIDE_LOOPS)
                    s.id in inTwo -> listOf(FlagCode.HOLE_IN_TWO_LOOPS)
                    else -> emptyList()
                },
            )
        }
        val groups = loops.mapIndexed { i, loop ->
            val mine = outShots.filter { owner[it.id] == i }
            val aim = loop.aimPointNumber?.let { aimAt[it] }
            val flags = ArrayList<Flag>()
            if (aim == null) flags += Flag(FlagCode.LOOP_WITHOUT_AIM_POINT, "No aim point picked for this group, so its offset can't be measured", centroid(loop.outlineMm))
            if (mine.any { it.id in inTwo }) flags += Flag(FlagCode.HOLE_IN_TWO_LOOPS, "Some holes are inside two loops; each went to the loop with the nearer aim point")
            if (expectedShots != null && mine.size < expectedShots) flags += Flag(FlagCode.FEWER_HOLES_THAN_FIRED, "${mine.size} holes found for ${expectedShots} shots; a shot may have gone through an earlier hole")
            if (expectedShots != null && mine.size > expectedShots) flags += Flag(FlagCode.MORE_HOLES_THAN_FIRED, "${mine.size} holes found for ${expectedShots} shots")
            GroupReading(
                id = ids[i], round = round, loopId = i + 1, colourName = loop.colourName, colourRgb = loop.colourRgb,
                aimPointNumber = loop.aimPointNumber, expectedShots = expectedShots, firedShots = expectedShots,
                detectedShots = mine.size, shotIds = mine.map { it.id },
                metrics = Metrics.group(mine, aim, distanceM), flags = flags,
            )
        }
        return Output(outShots, groups)
    }

    /** The aim point nearest the shots inside [outline] (or its middle), if one is within [maxMm]. */
    fun proposeAim(outline: List<PointMm>, shots: List<ShotReading>, aims: List<AimPoint>, maxMm: Double = 150.0): Int? {
        val inside = shots.filter { inside(it.centreMm, outline) }
        val c = if (inside.isNotEmpty()) PointMm(inside.sumOf { it.centreMm.x } / inside.size, inside.sumOf { it.centreMm.y } / inside.size) else centroid(outline)
        return aims.minByOrNull { (it.centreMm - c).radius }?.takeIf { (it.centreMm - c).radius <= maxMm }?.number
    }

    /** Is [p] inside the closed polygon [poly]? (Even-odd rule.) */
    fun inside(p: PointMm, poly: List<PointMm>): Boolean {
        if (poly.size < 3) return false
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]; val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    fun centroid(poly: List<PointMm>): PointMm =
        if (poly.isEmpty()) PointMm.ORIGIN else PointMm(poly.sumOf { it.x } / poly.size, poly.sumOf { it.y } / poly.size)

    /** Rough loop length in mm, to reject taps and scribbles too small to be a loop. */
    fun perimeterMm(poly: List<PointMm>): Double =
        poly.indices.sumOf { i -> val a = poly[i]; val b = poly[(i + 1) % poly.size]; hypot(a.x - b.x, a.y - b.y) }
}
