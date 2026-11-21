package rangetarget.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.hypot

/**
 * A point in the card frame, in millimetres.
 * Origin at the card centre (centre of the 10 ring), +x to the right and
 * +y up, as the shooter faces the card. Images have +y down; convert at the
 * edges, never inside the analysis.
 */
@Serializable
data class PointMm(val x: Double, val y: Double) {
    val radius: Double get() = hypot(x, y)
    operator fun plus(o: PointMm) = PointMm(x + o.x, y + o.y)
    operator fun minus(o: PointMm) = PointMm(x - o.x, y - o.y)

    companion object {
        val ORIGIN = PointMm(0.0, 0.0)
    }
}
