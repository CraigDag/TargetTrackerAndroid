package rangetarget.core.session

import rangetarget.core.geometry.PointMm
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One measured group, as far as patterns need it. */
data class PatternGroup(val shotsMm: List<PointMm>, val aimMm: PointMm?, val distanceM: Double)

/** Something a pattern shows, in plain words, with a question for the shooter (docs/SPEC.md, Patterns). */
data class PatternNote(val title: String, val text: String, val question: String)

/**
 * Patterns across many groups (docs/SPEC.md, Patterns). Groups shot at
 * different distances are put on one scale by angle, then shown as millimetres
 * at [referenceM] so the numbers mean something on the card. Like the round
 * observations, the notes say what the shots show and ask a question; the
 * diagnosis is left to the shooter.
 */
class Patterns(groups: List<PatternGroup>, val referenceM: Double = 12.0) {
    /** Groups with 2+ shots (a single shot has no spread). */
    val groups = groups.filter { it.shotsMm.size >= 2 && it.distanceM > 0 }
    val shotCount = this.groups.sumOf { it.shotsMm.size }

    /** Every shot's offset from its own group's middle, mm at [referenceM]. */
    val aroundMiddle: List<PointMm> = this.groups.flatMap { g ->
        val c = middle(g.shotsMm); val k = referenceM / g.distanceM
        g.shotsMm.map { PointMm((it.x - c.x) * k, (it.y - c.y) * k) }
    }

    /** Each group's middle relative to its aim point, mm at [referenceM]. Groups without an aim point are left out. */
    val middlesFromAim: List<PointMm> = this.groups.mapNotNull { g ->
        g.aimMm?.let { a -> val c = middle(g.shotsMm); val k = referenceM / g.distanceM; PointMm((c.x - a.x) * k, (c.y - a.y) * k) }
    }

    /** Typical side-to-side and up-and-down spread of shots about their group's middle (standard deviation, mm at [referenceM]). */
    val sideSpread: Double = sd(aroundMiddle.map { it.x })
    val upDownSpread: Double = sd(aroundMiddle.map { it.y })

    /** The direction shots spread most along, degrees anticlockwise from "right" (0–180), and how much longer that way than across it. */
    val mainDirectionDeg: Double
    val stretch: Double

    init {
        val p = aroundMiddle
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (q in p) { sxx += q.x * q.x; syy += q.y * q.y; sxy += q.x * q.y }
        val n = max(1, p.size)
        sxx /= n; syy /= n; sxy /= n
        val tr = sxx + syy; val det = sxx * syy - sxy * sxy
        val gap = sqrt(max(0.0, tr * tr / 4 - det))
        val l1 = tr / 2 + gap; val l2 = max(1e-9, tr / 2 - gap)
        stretch = sqrt(l1 / l2)
        mainDirectionDeg = (Math.toDegrees(0.5 * atan2(2 * sxy, sxx - syy)) + 180) % 180
    }

    /** Average of the group middles from their aim points, mm at [referenceM]. */
    val averageOffset: PointMm? = middlesFromAim.takeIf { it.isNotEmpty() }?.let { middle(it) }

    /** How much the group middles wander around their average (standard deviation of distance, mm at [referenceM]). */
    val middleWander: Double = averageOffset?.let { a -> sqrt(middlesFromAim.sumOf { val d = it - a; d.x * d.x + d.y * d.y } / middlesFromAim.size) } ?: 0.0

    /** Shots far from the rest of their group: over 2.5 × the group's average distance from its middle, in groups of 4+. */
    val strays: Int = this.groups.filter { it.shotsMm.size >= 4 }.sumOf { g ->
        val c = middle(g.shotsMm)
        val d = g.shotsMm.map { (it - c).radius }
        // Each shot against the others' typical distance, so one stray doesn't hide itself.
        d.indices.count { i -> val rest = d.filterIndexed { j, _ -> j != i }.average(); rest > 0.5 && d[i] > 2.5 * rest }
    }

    val enough: Boolean get() = groups.size >= MIN_GROUPS && shotCount >= MIN_SHOTS

    fun notes(): List<PatternNote> {
        if (!enough) return listOf(PatternNote("Not enough shots yet",
            "There ${if (shotCount == 1) "is 1 shot" else "are $shotCount shots"} in ${groups.size} measured group${if (groups.size == 1) "" else "s"} here. Patterns start to mean something from about $MIN_SHOTS shots in $MIN_GROUPS or more groups.",
            "Shoot a few more groups, then come back and see what stands out."))
        val out = ArrayList<PatternNote>()
        val at = "at ${metres(referenceM)}"

        // Shape of the spread.
        val ratio = sideSpread / max(1e-9, upDownSpread)
        val diagonal = stretch >= STRETCH && mainDirectionDeg.let { it in 25.0..65.0 || it in 115.0..155.0 }
        out += when {
            diagonal -> {
                val line = if (mainDirectionDeg < 90) "low left to high right" else "high left to low right"
                PatternNote("Shots spread along a slant",
                    "Around each group's middle, shots spread about ${fmt(stretch)} times further along a line from $line than across it.",
                    "What could push shots along that slant, and not across it?")
            }
            ratio >= STRETCH -> PatternNote("Shots spread side to side",
                "Around each group's middle, shots spread about ${fmt(ratio)} times more side to side than up and down (typically ${mm(sideSpread)} sideways, $at).",
                "What could move a shot sideways without moving it up or down?")
            1 / ratio >= STRETCH -> PatternNote("Shots spread up and down",
                "Around each group's middle, shots spread about ${fmt(1 / ratio)} times more up and down than side to side (typically ${mm(upDownSpread)} up and down, $at).",
                "What could change a shot's height from one shot to the next?")
            else -> PatternNote("Shots spread evenly",
                "Around each group's middle, shots spread about the same in every direction (typically ${mm((sideSpread + upDownSpread) / 2)} each way, $at).",
                "With no direction standing out, what would make every group smaller?")
        }

        // Where groups land.
        val off = averageOffset
        if (off != null && middlesFromAim.size >= MIN_GROUPS) {
            val sameSide = middlesFromAim.count { it.x * off.x + it.y * off.y > 0 }.toDouble() / middlesFromAim.size
            if (off.radius >= OFFSET_MM * referenceM / 12.0 && sameSide >= 0.7) out += PatternNote("Groups land off the aim point",
                "On average the middle of a group lands ${Metrics.words(off)} of its aim point ($at), and ${(sameSide * 100).roundToInt()}% of groups land on that side.",
                "When most groups land the same way, what is the same about every shot?")
            else out += PatternNote("Groups centre on the aim point",
                "On average the group middles land close to their aim points (${mm(off.radius)} away, $at), with no side standing out.",
                "What are you doing that keeps the groups centred?")
            val within = (sideSpread + upDownSpread) / 2
            if (middleWander > 1.5 * within && middlesFromAim.size >= MIN_GROUPS) out += PatternNote("Group middles move around",
                "The middle of the group moves from group to group by about ${mm(middleWander)}, more than the shots move within a group (${mm(within)}).",
                "What changes between groups: stance, grip, how you settle, the light, getting tired?")
        }

        if (strays > 0) out += PatternNote("Shots away from their group",
            "$strays shot${if (strays == 1) "" else "s"} landed well away from the rest of ${if (strays == 1) "its" else "their"} group.",
            "Do you remember how ${if (strays == 1) "that shot" else "those shots"} felt when the shot broke?")
        return out
    }

    companion object {
        const val MIN_GROUPS = 3
        const val MIN_SHOTS = 12
        /** Spread this much longer one way than the other counts as a shape. */
        const val STRETCH = 1.3
        /** Average offset (mm at 12 m) that counts as landing off the aim point. */
        const val OFFSET_MM = 6.0

        fun middle(p: List<PointMm>) = PointMm(p.sumOf { it.x } / p.size, p.sumOf { it.y } / p.size)
        private fun sd(v: List<Double>): Double = if (v.size < 2) 0.0 else sqrt(v.sumOf { it * it } / v.size)
        private fun fmt(x: Double) = if (x >= 10) "%.0f".format(x) else "%.1f".format(x)
        private fun mm(x: Double) = "%.0f mm".format(x)
        private fun metres(m: Double) = if (abs(m - m.roundToInt()) < 1e-9) "${m.roundToInt()} m" else "%.1f m".format(m)
    }
}
