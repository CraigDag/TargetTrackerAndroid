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
}
