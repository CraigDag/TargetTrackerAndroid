package rangetarget.core.flags

import kotlinx.serialization.Serializable
import rangetarget.core.geometry.PointMm

/**
 * Something the analysis is unsure about, with a plain reason. Never guess
 * silently: anything ambiguous becomes a flag (docs/SPEC.md, Edge cases).
 */
@Serializable
data class Flag(
    val code: FlagCode,
    val reason: String,
    /** Where on the card it applies, if anywhere in particular. */
    val atMm: PointMm? = null,
)

@Serializable
enum class FlagCode {
    /** The black aiming disc could not be found or looks wrong. */
    BLACK_DISC_DOUBTFUL,
    /** The printed rings could not be matched closely. */
    RING_FIT_POOR,
    /** One or more paper edges were not found; card rotation is less certain. */
    PAPER_EDGE_MISSING,
    /** Holes merged together; count and centres are estimates. */
    OVERLAPPING_HOLES,
    /** Hole cut by the paper edge; only part of it is visible. */
    HOLE_ON_PAPER_EDGE,
    /** Smaller or odder in shape than a normal hole; could be a tear or a mark. */
    HOLE_UNCERTAIN,
    /** Area too shot-out to separate holes (e.g. a reactive sticker). */
    UNRESOLVABLE_CLUSTER,

    // --- Session pipeline (Phase 3); see docs/SPEC.md, Edge cases. ---
    /** The card could not be found in a photo; that round can't be analysed. */
    CARD_NOT_FOUND,
    /** Fine alignment to the previous photo was weak; new holes may be missed or doubled. */
    ALIGNMENT_POOR,
    /** No session plan: distance assumed and shot counts not checked. */
    NO_PLAN,
    /** An aim point's number was read with low confidence; confirm it. */
    AIM_POINT_NUMBER_UNSURE,
    /** New hole inside two (or more) new loops; the nearest aim point's loop is proposed. */
    HOLE_IN_TWO_LOOPS,
    /** New hole outside every new loop: assign, tag as flyer, or ignore. */
    HOLE_OUTSIDE_LOOPS,
    /** New shot through an existing hole (the old hole grew); centre is estimated. */
    HOLE_GREW,
    /** A loop with a small gap; treated as closed. */
    LOOP_NOT_CLOSED,
    /** A loop that runs to or off the paper edge; the edge closes it. */
    LOOP_ON_PAPER_EDGE,
    /** New ink that isn't a closed loop (numbers, scribbles); ignored, shown grey. */
    STRAY_INK,
    /** The same loop colour drawn twice in one round. */
    COLOUR_USED_TWICE,
    /** A loop with no aim point near it; offset from aim can't be measured. */
    LOOP_WITHOUT_AIM_POINT,
    /** A loop far from every aim point, given to the only planned aim point left without a loop. */
    AIM_POINT_BY_ELIMINATION,
    /** Two loops around the same aim point. */
    AIM_POINT_TWO_LOOPS,
    /** Fewer holes found than shots fired; likely holes are suggested. */
    FEWER_HOLES_THAN_FIRED,
    /** More holes found than shots fired. */
    MORE_HOLES_THAN_FIRED,
    /** A merged blob re-fitted with fewer holes because its group had more holes than shots fired. */
    SPLIT_TO_MATCH_COUNT,

    // --- Photo check (docs/SPEC.md, Photo check): advice to retake. ---
    /** The photo is blurry (shake or missed focus). */
    PHOTO_BLURRY,
    /** The card is small in the photo (taken from too far away). */
    PHOTO_TOO_FAR,
    /** The photo was taken at a steep angle to the card. */
    PHOTO_TILTED,
    /** Part of the card is in shadow or much darker than the rest. */
    PHOTO_UNEVEN_LIGHT,
    /** Glare has burnt part of the card out to white. */
    PHOTO_GLARE,
}
