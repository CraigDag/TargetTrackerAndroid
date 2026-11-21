package rangetarget.core.groundtruth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import rangetarget.core.RangeJson
import rangetarget.core.geometry.PointMm
import java.io.File

/**
 * Ground truth written by the Python synthetic generator next to every image.
 * The format is documented in docs/ground-truth-schema.md; keep the two in step
 * and bump [SCHEMA_VERSION] on any incompatible change.
 *
 * Each file stands alone: it describes everything on the card at the moment the
 * photo was taken (all shots and loops so far), tagged with the round that
 * added them.
 */
@Serializable
data class GroundTruth(
    val schemaVersion: Int,
    val sessionId: String,
    val preset: String,
    val seed: Long,
    val image: ImageInfo,
    /** 0 for the baseline photo, then 1, 2, ... for each mark round. */
    val round: Int,
    /** Details of this photo's mark round; null for the baseline. */
    val roundInfo: RoundInfo?,
    val aimPoints: List<AimPoint>,
    val groups: List<Group>,
    val shots: List<Shot>,
    val loops: List<Loop>,
    val distortion: Distortion,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        fun parse(json: String): GroundTruth {
            val gt = RangeJson.decodeFromString(serializer(), json)
            require(gt.schemaVersion == SCHEMA_VERSION) {
                "Ground truth schema version ${gt.schemaVersion} not supported (expected $SCHEMA_VERSION)"
            }
            return gt
        }

        fun read(file: File): GroundTruth = parse(file.readText())
    }

    fun toJson(): String = RangeJson.encodeToString(this) + "\n"
}

@Serializable
data class ImageInfo(
    /** File name of the photo, relative to this JSON file. */
    val file: String,
    val widthPx: Int,
    val heightPx: Int,
)

@Serializable
data class RoundInfo(
    val distanceM: Double,
    /** Diameter of a clean hole this round, set by calibre. */
    val holeDiameterMm: Double,
    val calibre: String,
)

@Serializable
enum class AimPointKind {
    @SerialName("sticker") STICKER,
    @SerialName("hand_drawn") HAND_DRAWN,
}

@Serializable
data class AimPoint(
    val number: Int,
    val kind: AimPointKind,
    /** sRGB colour as "#RRGGBB". */
    val colour: String,
    val centreMm: PointMm,
    val diameterMm: Double,
)

@Serializable
data class Group(
    /** Unique within the session, e.g. "r1g2". */
    val id: String,
    val round: Int,
    /** Aim point the group was fired at, or null for none. */
    val aimPointNumber: Int?,
    /** Name of the loop colour, e.g. "yellow". */
    val loopColour: String,
    val expectedShots: Int,
    val firedShots: Int,
)

@Serializable
data class Shot(
    /** Unique within the session, e.g. "r1g2s3". */
    val id: String,
    val round: Int,
    /** The group this shot belongs to, or null if it was left unlooped. */
    val groupId: String?,
    val centreMm: PointMm,
    val diameterMm: Double,
    /** True for a deliberate flyer (well outside the group's scatter). */
    val flyer: Boolean = false,
    /** Id of an earlier shot whose hole this one passes through, partly or fully. */
    val throughShotId: String? = null,
    /** True if this hole is hidden entirely inside another (cannot be seen). */
    val invisible: Boolean = false,
)

@Serializable
data class Loop(
    val groupId: String,
    val round: Int,
    /** Colour name and sRGB value as drawn. */
    val colour: String,
    val colourRgb: String,
    /** The loop's centre line in card mm, in drawing order. */
    val pathMm: List<PointMm>,
    val closed: Boolean,
    val strokeWidthMm: Double,
)

/**
 * How the clean card was turned into the "phone photo". The mapping from card
 * mm to image pixels is the homography followed by the curl; with zero curl
 * the homography alone is exact.
 */
@Serializable
data class Distortion(
    /** Row-major 3×3 homography taking card mm (x right, y up) to image px (x right, y down). */
    val homographyMmToPx: List<Double>,
    val rotationDeg: Double,
    val tiltXDeg: Double,
    val tiltYDeg: Double,
    /** Bend of the paper as a cylinder, as the bulge in mm at the card centre; 0 for flat. */
    val curlMm: Double,
    val curlAxisDeg: Double,
    /** Brightness falls from 1.0 at the brightest corner to this value at the darkest. */
    val lightingMin: Double,
    val blurSigmaPx: Double,
    val noiseSigma: Double,
    val jpegQuality: Int,
) {
    init {
        require(homographyMmToPx.size == 9) { "homography_mm_to_px must have 9 numbers" }
    }
}
