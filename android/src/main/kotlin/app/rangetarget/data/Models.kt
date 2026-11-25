package app.rangetarget.data

import kotlinx.serialization.Serializable
import rangetarget.core.flags.Flag
import rangetarget.core.geometry.PointMm
import rangetarget.core.session.AimPoint
import rangetarget.core.session.DrawnGroups
import rangetarget.core.session.DrawnLoop
import rangetarget.core.session.ShotReading

// What the app keeps on the phone (docs/SPEC.md, Data model). Each session is
// one JSON file next to its photos; kit and settings are one file each.

@Serializable
data class Settings(
    val name: String = "",
    /** "dark" (default), "light" or "system". */
    val theme: String = "dark",
    val defaultDistanceM: Double = 12.0,
)

@Serializable
data class Gun(
    val id: String,
    val name: String,
    val calibre: String = "",
    /** "Iron Sight", "Red Dot" or "Other". */
    val sight: String = "Iron Sight",
    val clubNumber: String = "",
)

@Serializable
data class Ammo(
    val id: String,
    val name: String,
    val brand: String = "",
    val calibre: String = "",
    val bulletWeightGr: String = "",
    /** "Full metal jacket", "Lead round nose", "Wadcutter" or "Other". */
    val bulletType: String = "",
    val lot: String = "",
)

@Serializable
data class TargetItem(
    val id: String,
    val name: String,
    val type: String = "",
)

@Serializable
data class Kit(
    val guns: List<Gun> = emptyList(),
    val ammo: List<Ammo> = emptyList(),
    val targets: List<TargetItem> = emptyList(),
)

/** A round's hand-made state: loops drawn, holes removed as false, holes added that were missed. */
@Serializable
data class Marking(
    val loops: List<DrawnLoop> = emptyList(),
    val removedShotIds: List<String> = emptyList(),
    val addedShots: List<ShotReading> = emptyList(),
)

/** One change made after the groups were first saved (Markup). [after] is the state it left. */
@Serializable
data class Edit(val atMillis: Long, val what: String, val after: Marking)

@Serializable
data class StoredRound(
    val number: Int,
    /** File names inside the session folder. */
    val photo: String,
    val view: String,
    val distanceM: Double,
    val gunId: String? = null,
    val ammoId: String? = null,
    /** New holes found in this round's photo (group fields are filled in from [loops]). */
    val shots: List<ShotReading>,
    /** The current groups and hole fixes (results are always from this). */
    val marking: Marking = Marking(),
    /** The marking as first saved; null until the groups are saved once. The photo is never changed. */
    val original: Marking? = null,
    val edits: List<Edit> = emptyList(),
    val flags: List<Flag> = emptyList(),
    /** Painted loops the core read from the photo. Not used in v1 except to fill in the sample session automatically. */
    val paintLoops: List<DrawnLoop> = emptyList(),
    /** Where a shot may be hidden (older holes, big holes), offered when a group is short of holes. */
    val hints: List<rangetarget.core.session.HoleHint> = emptyList(),
    val analysisMillis: Long = 0,
    /** The shooter's own note on this round: what they felt, what they'll try next. */
    val note: String = "",
    val takenAt: Long = System.currentTimeMillis(),
) {
    /** Holes as the shooter has confirmed them: found ones, less false ones, plus added ones. */
    fun effectiveShots(m: Marking = marking): List<ShotReading> = shots.filter { it.id !in m.removedShotIds } + m.addedShots
}

@Serializable
data class StoredSession(
    val id: String,
    val createdAt: Long,
    val drill: String,
    val groupsPerRound: Int,
    /** Null for a free drill (no count check). */
    val shotsPerGroup: Int?,
    val distanceM: Double,
    val gunId: String? = null,
    val ammoId: String? = null,
    /** Added by hand from a gallery photo (Markup, "from device"). */
    val manual: Boolean = false,
    val baselinePhoto: String? = null,
    val baselineView: String? = null,
    val baselineFlags: List<Flag> = emptyList(),
    val aims: List<AimPoint> = listOf(CENTRE),
    val rounds: List<StoredRound> = emptyList(),
    val finished: Boolean = false,
    val notes: String = "",
    /** The sample session made from the app's built-in photos. */
    val demo: Boolean = false,
) {
    fun round(n: Int) = rounds.firstOrNull { it.number == n }

    /** Groups for a round, measured from its loops. */
    fun groups(r: StoredRound): DrawnGroups.Output =
        DrawnGroups.measure(r.number, r.effectiveShots(), r.marking.loops, aims, r.distanceM, shotsPerGroup)

    /** Average group size across the session (extreme spread of groups with 2+ shots), mm and mrad. */
    fun averageSize(): Pair<Double, Double>? {
        val m = rounds.flatMap { groups(it).groups }.mapNotNull { it.metrics }.filter { it.shots >= 2 }
        if (m.isEmpty()) return null
        return m.map { it.extremeSpreadMm }.average() to m.map { it.extremeSpreadMrad }.average()
    }

    companion object {
        val CENTRE = AimPoint(0, PointMm.ORIGIN, "centre")
    }
}

/** Drill templates (docs/SPEC.md, Session plan). Cadence waits for labels (after v1). */
data class Drill(val name: String, val groups: Int, val shots: Int?, val about: String)

val DRILLS = listOf(
    Drill("Grouping", 4, 5, "4 groups of 5, one per aim point. Leads with group size."),
    Drill("Centre check", 2, 10, "2 groups of 10. Leads with how far the group centre is from the aim point."),
    Drill("Precision series", 1, 5, "1 group of 5 at the centre, repeated each round. Leads with score."),
    Drill("Free", 1, null, "Any number of groups and shots. No count check."),
)

/** Colours the app draws groups in, one per loop in a round. */
val LOOP_COLOURS = listOf(
    "yellow" to "#FFD54F", "pink" to "#F06292", "lime" to "#AED581", "light blue" to "#4FC3F7",
    "orange" to "#FFB74D", "white" to "#FFFFFF", "violet" to "#B39DDB", "teal" to "#4DB6AC",
)
