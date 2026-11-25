package rangetarget.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.PointMm

// ---------------------------------------------------------------------------
// Input: the session plan (docs/session-files.md). What the shooter planned and
// fired; the pipeline uses it for distances, hole size and shot counts.
// ---------------------------------------------------------------------------

@Serializable
data class SessionPlan(
    val rounds: List<RoundPlan> = emptyList(),
    /** Free text saying where the plan came from (e.g. "typed by the shooter"). */
    val source: String = "",
) {
    fun round(n: Int): RoundPlan? = rounds.firstOrNull { it.round == n }
}

@Serializable
data class RoundPlan(
    val round: Int,
    val distanceM: Double = DEFAULT_DISTANCE_M,
    /** Clean hole diameter for the round's calibre, if known. Measured from the photo otherwise. */
    val holeDiameterMm: Double? = null,
    val calibre: String? = null,
    val groups: List<GroupPlan> = emptyList(),
) {
    companion object { const val DEFAULT_DISTANCE_M = 12.0 }
}

@Serializable
data class GroupPlan(
    /** Aim point the group is fired at (matches the number read in the baseline). */
    val aimPointNumber: Int? = null,
    /** Loop colour name, if the shooter said which; used only when there's no aim point. */
    val colour: String? = null,
    val expectedShots: Int,
    /** Shots actually fired, if different from planned. */
    val firedShots: Int? = null,
)

// ---------------------------------------------------------------------------
// Output. Written to session.json by the desktop tool; the phone app will show
// the same data on its confirm and results screens.
// ---------------------------------------------------------------------------

@Serializable
enum class AimPointStyle {
    @SerialName("sticker") STICKER,
    @SerialName("hand_drawn") HAND_DRAWN,
}

@Serializable
data class AimPointReading(
    /** Position in the list, from 1 (not the number written on it). */
    val id: Int,
    val centreMm: PointMm,
    val diameterMm: Double,
    val style: AimPointStyle,
    val colourRgb: String,
    val colourName: String,
    /** Number read from the disc, as a guess; null if nothing readable. */
    val number: Int?,
    /** 0..1: how clearly the best number beat the next best. */
    val numberConfidence: Double,
    /** Next most likely readings, best first. */
    val numberAlternatives: List<Int>,
    val flags: List<Flag>,
)

@Serializable
data class BaselineReading(
    val photo: String,
    val card: CardSummary?,
    val aimPoints: List<AimPointReading>,
    /** Holes already in the card before the first round (should be none on a fresh card). */
    val existingHoles: Int,
    val flags: List<Flag>,
    /** How good the photo is for finding holes (docs/SPEC.md, Photo check). */
    val quality: PhotoQuality? = null,
)

@Serializable
data class CardSummary(
    val pxPerMmAtCentre: Double,
    val ringMissMedianMm: Double,
    val ringMissP95Mm: Double,
    val turnCorrectionDeg: Double,
    val largestBendMm: Double,
)

@Serializable
data class AlignmentSummary(
    /** Patches of the card used to line this photo up with the previous one. */
    val patchesUsed: Int,
    val patchesTried: Int,
    /** Largest correction applied on top of the card fit, in mm. */
    val largestCorrectionMm: Double,
    /** How far patches still disagree after alignment: typical and 95%, in mm. */
    val leftoverMedianMm: Double,
    val leftoverP95Mm: Double,
)

@Serializable
enum class InkKind {
    @SerialName("loop") LOOP,
    @SerialName("loop_with_gap") LOOP_WITH_GAP,
    /** Runs to (or off) the paper edge; the edge closes it. */
    @SerialName("loop_at_edge") LOOP_AT_EDGE,
    @SerialName("stray") STRAY,
}

@Serializable
data class InkMark(
    val id: Int,
    val colourName: String,
    val colourRgb: String,
    val kind: InkKind,
    /** Outer edge of the mark (for a loop: the area it encloses, stroke included). */
    val outlineMm: List<PointMm>,
    val centreMm: PointMm,
    /** Ink area, mm². */
    val inkAreaMm2: Double,
)

@Serializable
enum class ShotSource {
    /** A clean, separate new hole. */
    @SerialName("clean") CLEAN,
    /** One of several new holes merged into one blob; centre estimated. */
    @SerialName("merged") MERGED,
    /** Went through or touched an existing hole, which grew; centre estimated. */
    @SerialName("grown_hole") GROWN_HOLE,
}

@Serializable
data class ShotReading(
    /** e.g. "r2s07": round 2, shot 7 (numbered top to bottom). */
    val id: String,
    val round: Int,
    val groupId: String?,
    val centreMm: PointMm,
    /** Offset from the group's aim point, if there is one. */
    val fromAimMm: PointMm?,
    /** Hole diameter used for scoring. */
    val diameterMm: Double,
    /** Whole-ring score by hole edge (a hole touching a ring line takes the higher ring). */
    val score: Int,
    val source: ShotSource,
    /** 0..1, how sure the hole and its centre are. */
    val confidence: Double,
    val flags: List<FlagCode>,
)

@Serializable
data class GroupMetrics(
    val shots: Int,
    val centreMm: PointMm,
    /** Group centre minus aim point centre, mm (+x right, +y up). */
    val offsetFromAimMm: PointMm?,
    /** Plain words, e.g. "14 mm low left". */
    val offsetWords: String?,
    /** Largest centre-to-centre distance between two shots. */
    val extremeSpreadMm: Double,
    /** Average distance of the shots from the group centre. */
    val meanRadiusMm: Double,
    val horizontalSpreadMm: Double,
    val verticalSpreadMm: Double,
    val totalScore: Int,
    val distanceM: Double,
    /** The same sizes as angles at the round's distance (1 mrad = 1 mm per metre). */
    val extremeSpreadMrad: Double,
    val meanRadiusMrad: Double,
    val horizontalSpreadMrad: Double,
    val verticalSpreadMrad: Double,
    val offsetMrad: PointMm?,
    /** The two shots that set the extreme spread. */
    val extremePairIds: List<String>,
)

@Serializable
data class GroupReading(
    /** e.g. "r2g1": round 2, group with the lowest aim-point number first. */
    val id: String,
    val round: Int,
    val loopId: Int,
    val colourName: String,
    val colourRgb: String,
    val aimPointNumber: Int?,
    val expectedShots: Int?,
    val firedShots: Int?,
    val detectedShots: Int,
    val shotIds: List<String>,
    val metrics: GroupMetrics?,
    val flags: List<Flag>,
)

@Serializable
data class RoundResult(
    val round: Int,
    val photo: String,
    val distanceM: Double,
    /** Hole diameter used: from the plan, or measured from this round's clean holes. */
    val holeDiameterMm: Double,
    val holeDiameterSource: String,
    val card: CardSummary?,
    val alignment: AlignmentSummary?,
    val ink: List<InkMark>,
    val shots: List<ShotReading>,
    val groups: List<GroupReading>,
    val flags: List<Flag>,
    /** How good the photo is for finding holes (docs/SPEC.md, Photo check). */
    val quality: PhotoQuality? = null,
    /** Places where a shot may be hidden, for a group short of holes (docs/SPEC.md, Hidden shots). */
    val hints: List<HoleHint> = emptyList(),
)

/**
 * A place where a shot may be hidden: an older hole (a new shot can pass
 * straight through one and leave no trace) or a new hole bigger than the
 * holes fitted in it (two shots touching). Shown only as a suggestion.
 */
@Serializable
data class HoleHint(
    val centreMm: PointMm,
    /** "older" or "big". */
    val kind: String,
)

@Serializable
data class RoundSummaryLine(
    val round: Int,
    val groups: Int,
    val shotsFound: Int,
    val shotsFired: Int?,
    val unassignedShots: Int,
    val flags: Int,
    val meanExtremeSpreadMm: Double?,
    val meanMeanRadiusMm: Double?,
)

@Serializable
data class SessionSummary(
    val rounds: List<RoundSummaryLine>,
    val totalShotsFound: Int,
    val totalFlags: Int,
    /** Average group size across the session, in mrad so 12 m and 25 m compare. */
    val meanExtremeSpreadMrad: Double?,
    val meanMeanRadiusMrad: Double?,
)

@Serializable
data class SessionResult(
    val coreVersion: String,
    val planSource: String,
    val baseline: BaselineReading,
    val rounds: List<RoundResult>,
    val summary: SessionSummary,
)
