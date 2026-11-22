package rangetarget.core.session

/**
 * One-line round observations phrased as a question (docs/SPEC.md, App v1,
 * Round results). They point at what the numbers show and leave the diagnosis
 * to the shooter.
 */
object Observations {
    fun round(groups: List<GroupReading>): String {
        val measured = groups.filter { (it.metrics?.shots ?: 0) >= 2 }
        if (measured.isEmpty()) return if (groups.isEmpty()) "No groups circled yet." else "Too few holes in a group to measure it. Did every shot land on the card?"
        val directions = measured.mapNotNull { g -> g.metrics?.offsetWords?.takeIf { it != "on the aim point" }?.substringAfter(" mm ")?.split(" ")?.toSet() }
        if (measured.size >= 2 && directions.size == measured.size) {
            val shared = directions.reduce { a, b -> a intersect b }
            if (shared.isNotEmpty()) return "Every group landed ${shared.sorted().joinToString(" ")} of its aim point. What might move every shot the same way?"
        }
        if (measured.size >= 2) {
            val big = measured.maxBy { it.metrics!!.extremeSpreadMm }; val small = measured.minBy { it.metrics!!.extremeSpreadMm }
            if (big.metrics!!.extremeSpreadMm > 1.6 * small.metrics!!.extremeSpreadMm)
                return "Group ${big.loopId} was much bigger than group ${small.loopId}. What felt different while you shot it?"
            return "The groups were a similar size. What did you do the same each time?"
        }
        val m = measured.single().metrics!!
        return m.offsetWords?.let { if (it == "on the aim point") "This group is centred on its aim point. What did you focus on?" else "This group landed $it. Where did you think the shots went?" }
            ?: "How did this group feel compared with how it looks?"
    }
}
