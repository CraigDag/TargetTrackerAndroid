package rangetarget.core.session

import rangetarget.core.geometry.PointMm
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Group numbers (docs/SPEC.md, Metrics and results). All sizes are from hole
 * centres. Angles use the round's distance: 1 mrad is 1 mm per metre, so a
 * 24 mm group at 12 m and a 50 mm group at 25 m are both 2 mrad.
 */
object Metrics {
    fun group(shots: List<ShotReading>, aim: PointMm?, distanceM: Double): GroupMetrics? {
        if (shots.isEmpty()) return null
        val n = shots.size
        val cx = shots.sumOf { it.centreMm.x } / n; val cy = shots.sumOf { it.centreMm.y } / n
        val centre = PointMm(cx, cy)
        var es = 0.0; var pair = listOf(shots[0].id)
        for (i in shots.indices) for (j in i + 1 until n) {
            val d = hypot(shots[i].centreMm.x - shots[j].centreMm.x, shots[i].centreMm.y - shots[j].centreMm.y)
            if (d > es) { es = d; pair = listOf(shots[i].id, shots[j].id) }
        }
        val mr = shots.sumOf { hypot(it.centreMm.x - cx, it.centreMm.y - cy) } / n
        val hs = shots.maxOf { it.centreMm.x } - shots.minOf { it.centreMm.x }
        val vs = shots.maxOf { it.centreMm.y } - shots.minOf { it.centreMm.y }
        val off = aim?.let { centre - it }
        val mrad = { mm: Double -> mm / distanceM }
        return GroupMetrics(
            shots = n, centreMm = centre, offsetFromAimMm = off, offsetWords = off?.let { words(it) },
            extremeSpreadMm = es, meanRadiusMm = mr, horizontalSpreadMm = hs, verticalSpreadMm = vs,
            totalScore = shots.sumOf { it.score }, distanceM = distanceM,
            extremeSpreadMrad = mrad(es), meanRadiusMrad = mrad(mr), horizontalSpreadMrad = mrad(hs), verticalSpreadMrad = mrad(vs),
            offsetMrad = off?.let { PointMm(mrad(it.x), mrad(it.y)) }, extremePairIds = pair,
        )
    }

    /** "14 mm low left", "6 mm high", "on the aim point". */
    fun words(off: PointMm): String {
        val r = off.radius
        if (r < 2.0) return "on the aim point"
        val parts = ArrayList<String>()
        if (abs(off.y) >= max(2.0, 0.38 * r)) parts += if (off.y > 0) "high" else "low"
        if (abs(off.x) >= max(2.0, 0.38 * r)) parts += if (off.x > 0) "right" else "left"
        return "%.0f mm %s".format(r, parts.joinToString(" "))
    }

    fun session(rounds: List<RoundResult>): SessionSummary {
        val lines = rounds.map { r ->
            val withMetrics = r.groups.mapNotNull { it.metrics }.filter { it.shots >= 2 }
            RoundSummaryLine(
                round = r.round, groups = r.groups.size, shotsFound = r.shots.size,
                shotsFired = r.groups.mapNotNull { it.firedShots ?: it.expectedShots }.takeIf { it.size == r.groups.size && it.isNotEmpty() }?.sum(),
                unassignedShots = r.shots.count { it.groupId == null },
                flags = r.flags.size + r.groups.sumOf { it.flags.size },
                meanExtremeSpreadMm = withMetrics.map { it.extremeSpreadMm }.averageOrNull(),
                meanMeanRadiusMm = withMetrics.map { it.meanRadiusMm }.averageOrNull(),
            )
        }
        val all = rounds.flatMap { r -> r.groups.mapNotNull { it.metrics } }.filter { it.shots >= 2 }
        return SessionSummary(lines, rounds.sumOf { it.shots.size }, lines.sumOf { it.flags },
            all.map { it.extremeSpreadMrad }.averageOrNull(), all.map { it.meanRadiusMrad }.averageOrNull())
    }

    private fun List<Double>.averageOrNull() = if (isEmpty()) null else average()
}
