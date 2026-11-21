package rangetarget.desktop

import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.RangeJson
import rangetarget.core.card.CardReader
import rangetarget.core.card.CardReading
import rangetarget.core.debug.DebugSink
import rangetarget.core.image.Rotation
import java.io.File

/** Writes debug images as JPEGs into [dir]. */
class FileSink(private val dir: File, private val log: (String) -> Unit) : DebugSink {
    init { dir.mkdirs() }
    override fun save(name: String, image: Mat) {
        val f = File(dir, "$name.jpg")
        Imgcodecs.imwrite(f.path, image, org.opencv.core.MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90))
        image.release()
        log("  wrote ${f.path}")
    }
}

internal fun card(args: List<String>, out: (String) -> Unit): Int {
    val opts = Options.parse(args, valued = setOf("--out", "--rotate"))
    val rotation = opts.values["--rotate"]?.let {
        Rotation.fromCli(it) ?: throw UsageException("--rotate must be left, right, 180 or none")
    } ?: Rotation.NONE
    val photo = opts.positional.singleOrNull() ?: throw UsageException("card needs exactly one photo")
    val input = File(photo)
    if (!input.isFile) throw UsageException("No such file: $photo")
    val outDir = File(opts.values["--out"] ?: "out/phase1")

    OpenCvNatives.load()
    val raw = Imgcodecs.imread(input.path, Imgcodecs.IMREAD_COLOR)
    if (raw.empty()) { System.err.println("Error: OpenCV could not read $photo as an image"); return 1 }
    val image = rotation.apply(raw); raw.release()
    val sink = FileSink(outDir, out)
    sink.save("01_input_upright", image.clone())
    val reading = CardReader.read(image, sink)
    image.release()
    if (reading == null) { out("Could not find the black aiming disc."); return 1 }
    File(outDir, "card.json").writeText(RangeJson.encodeToString(CardReading.serializer(), reading) + "\n")
    File(outDir, "holes.csv").writeText(holesCsv(reading))
    out("  wrote ${File(outDir, "card.json").path} and holes.csv")
    out(summary(reading))
    return 0
}

fun holesCsv(r: CardReading): String = buildString {
    appendLine("id,x_mm,y_mm,ring_by_centre,zone,blob_diameter_mm,blob_id,shots_in_blob,flags")
    for (h in r.holes) {
        appendLine(listOf(h.id, "%.1f".format(h.centreMm.x), "%.1f".format(h.centreMm.y), h.ringByCentre, h.zone,
            "%.1f".format(h.blobDiameterMm), h.blobId ?: "", h.shotsInBlob, h.flags.joinToString(";")).joinToString(","))
    }
}

fun summary(r: CardReading): String = buildString {
    val f = r.card
    appendLine("Card found: centre at (%.0f, %.0f) px, %.2f px/mm".format(f.cardToPhoto.toPx(rangetarget.core.geometry.PointMm.ORIGIN).x,
        f.cardToPhoto.toPx(rangetarget.core.geometry.PointMm.ORIGIN).y, f.pxPerMmAtCentre))
    appendLine("Rings: printed lines within %.2f mm of the ideal rings typically, %.2f mm for 95%% (%d points used, %d ignored)"
        .format(f.ringMissMedianMm, f.ringMissP95Mm, f.ringPointsUsed, f.ringPointsIgnored))
    appendLine("Paper: %s; turned %.2f deg to square it".format(
        if (f.paperWidthMm != null && f.paperHeightMm != null) "%.0f x %.0f mm".format(f.paperWidthMm, f.paperHeightMm)
        else "edges found: ${f.paperEdgesFound}", f.turnCorrectionDeg))
    val single = r.holes.count { it.confident }
    val merged = r.holes.count { it.shotsInBlob > 1 }
    val blobs = r.holes.mapNotNull { it.blobId }.toSet().size
    val unsure = r.holes.count { !it.confident && it.shotsInBlob == 1 }
    appendLine("Holes: ${r.holes.size} listed = $single clear singles + $merged in $blobs merged blobs + $unsure unsure")
    for (c in r.clusters) appendLine("Cluster at (%.0f, %.0f) mm, r %.0f mm: about %.0f sq mm of paper gone, at least %d shots".format(
        c.centreMm.x, c.centreMm.y, c.radiusMm, c.missingAreaMm2, c.roughShotsAtLeast))
    for (fl in r.flags) appendLine("Flag ${fl.code}: ${fl.reason}")
}
