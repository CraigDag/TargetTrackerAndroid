package rangetarget.core.session

import rangetarget.core.geometry.PointMm

/**
 * Calling groups (docs/SPEC.md, Calling your groups): before walking down, the
 * shooter taps where they think each group's middle landed. Afterwards each
 * call is matched to the nearest group middle, so the shooter can see how well
 * they read their own shots. Nothing is judged; the gap is just shown.
 */
object Calls {
    /** A call further than this from every group middle is left unmatched. */
    const val MAX_MM = 80.0

    /** One call matched to one group: the group's index and how far its middle landed from the call. */
    data class Match(val groupIndex: Int, val call: PointMm, val landedFromCall: PointMm)

    /**
     * Matches calls to group middles, closest pairs first, each used at most once.
     * Groups without a middle (null) are skipped.
     */
    fun match(middles: List<PointMm?>, calls: List<PointMm>, maxMm: Double = MAX_MM): List<Match> {
        val pairs = ArrayList<Triple<Int, Int, Double>>()
        middles.forEachIndexed { g, m -> if (m != null) calls.forEachIndexed { c, p -> val d = (m - p).radius; if (d <= maxMm) pairs += Triple(g, c, d) } }
        val usedG = HashSet<Int>(); val usedC = HashSet<Int>()
        val out = ArrayList<Match>()
        for ((g, c, _) in pairs.sortedBy { it.third }) {
            if (g in usedG || c in usedC) continue
            usedG += g; usedC += c
            out += Match(g, calls[c], middles[g]!! - calls[c])
        }
        return out.sortedBy { it.groupIndex }
    }

    /** Plain words for where a group landed relative to its call, e.g. "8 mm low left of your call". */
    fun words(m: Match): String {
        val w = Metrics.words(m.landedFromCall)
        return if (w == "on the aim point") "right where you called it" else "$w of your call"
    }
}
