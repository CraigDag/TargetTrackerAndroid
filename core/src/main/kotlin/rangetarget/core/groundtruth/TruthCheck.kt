package rangetarget.core.groundtruth

import kotlinx.serialization.Serializable
import rangetarget.core.geometry.PointMm
import rangetarget.core.session.AimPointReading
import rangetarget.core.session.GroupPlan
import rangetarget.core.session.RoundPlan
import rangetarget.core.session.RoundResult
import rangetarget.core.session.SessionPlan
import kotlin.math.hypot

/**
 * Quick comparison of a session result with the generator's known answers.
 * A sanity check for development and the end-to-end test; the full
 * validation report (Phase 4) is a Python tool working from the JSON files.
 */
object TruthCheck {
    /** Two centres closer than this are the same hole. */
    const val MATCH_MM = 6.0

    @Serializable
    data class RoundCheck(
        val round: Int,
        val trueShots: Int,
        val trueVisible: Int,
        val found: Int,
        val matched: Int,
        val missed: List<String>,
        val falseHoles: List<PointMm>,
        val medianErrorMm: Double?,
        val p95ErrorMm: Double?,
        val maxErrorMm: Double?,
        val assignedRight: Int,
        val assignedWrong: List<String>,
    ) {
        val recall get() = if (trueVisible == 0) 1.0 else matched.toDouble() / trueVisible
        val assignmentRate get() = if (matched == 0) 1.0 else assignedRight.toDouble() / matched
    }

    @Serializable
    data class AimCheck(val trueCount: Int, val found: Int, val matched: Int, val medianErrorMm: Double?, val maxErrorMm: Double?, val numbersRight: Int)

    fun round(r: RoundResult, gt: GroundTruth): RoundCheck {
        val truth = gt.shots.filter { it.round == r.round }
        val visible = truth.filter { !it.invisible }
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        for ((i, t) in visible.withIndex()) for ((j, s) in r.shots.withIndex()) {
            val d = dist(t.centreMm, s.centreMm)
            if (d <= MATCH_MM) pairs += Triple(i, j, d)
        }
        pairs.sortBy { it.third }
        val tUsed = HashSet<Int>(); val sUsed = HashSet<Int>(); val matches = ArrayList<Triple<Int, Int, Double>>()
        for (p in pairs) if (p.first !in tUsed && p.second !in sUsed) { tUsed += p.first; sUsed += p.second; matches += p }

        // Which true group each found group stands for: same aim point, else majority of its matched shots.
        val trueGroupOfAim = gt.groups.filter { it.round == r.round }.associate { it.aimPointNumber to it.id }
        val groupMap = r.groups.associate { g ->
            val byAim = g.aimPointNumber?.let { trueGroupOfAim[it] }
            g.id to (byAim ?: matches.filter { r.shots[it.second].groupId == g.id }.mapNotNull { visible[it.first].groupId }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key)
        }
        var right = 0; val wrong = ArrayList<String>()
        for ((ti, si, _) in matches) {
            val s = r.shots[si]; val t = visible[ti]
            val mapped = s.groupId?.let { groupMap[it] }
            if (mapped == t.groupId) right++ else wrong += "${s.id} (true ${t.id}, given ${s.groupId ?: "none"})"
        }
        val err = matches.map { it.third }.sorted()
        return RoundCheck(
            round = r.round, trueShots = truth.size, trueVisible = visible.size, found = r.shots.size, matched = matches.size,
            missed = visible.indices.filter { it !in tUsed }.map { visible[it].id },
            falseHoles = r.shots.indices.filter { it !in sUsed }.map { r.shots[it].centreMm },
            medianErrorMm = err.getOrNull(err.size / 2), p95ErrorMm = err.getOrNull((err.size * 0.95).toInt().coerceAtMost(err.size - 1)),
            maxErrorMm = err.lastOrNull(), assignedRight = right, assignedWrong = wrong,
        )
    }

    fun aimPoints(found: List<AimPointReading>, gt: GroundTruth): AimCheck {
        val errs = ArrayList<Double>(); var numbers = 0
        for (t in gt.aimPoints) {
            val f = found.minByOrNull { dist(it.centreMm, t.centreMm) } ?: continue
            val d = dist(f.centreMm, t.centreMm)
            if (d > t.diameterMm / 2) continue
            errs += d
            if (f.number == t.number) numbers++
        }
        errs.sort()
        return AimCheck(gt.aimPoints.size, found.size, errs.size, errs.getOrNull(errs.size / 2), errs.lastOrNull(), numbers)
    }

    /**
     * The plan the shooter would have entered, taken from the ground truth
     * (distance, calibre, groups with their aim point and shots fired). Only
     * for testing on synthetic sessions.
     */
    fun planFrom(truths: List<GroundTruth>): SessionPlan = SessionPlan(
        rounds = truths.filter { it.round > 0 && it.roundInfo != null }.map { gt ->
            RoundPlan(gt.round, gt.roundInfo!!.distanceM, gt.roundInfo.holeDiameterMm, gt.roundInfo.calibre,
                gt.groups.filter { it.round == gt.round }.map { GroupPlan(it.aimPointNumber, it.loopColour, it.expectedShots, it.firedShots) })
        },
        source = "taken from the synthetic ground truth (test only)",
    )

    private fun dist(a: PointMm, b: PointMm) = hypot(a.x - b.x, a.y - b.y)
}
