package rangetarget.desktop

import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.card.CardFinder
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.session.CardZones
import rangetarget.core.session.PhotoCheck
import rangetarget.core.session.ViewBox
import java.io.File
import kotlin.math.abs

/** `photocheck <photo or folder>...`: the photo check's numbers for each photo, as CSV. */
internal fun photocheck(args: List<String>, out: (String) -> Unit): Int {
    val opts = Options.parse(args, valued = emptySet())
    if (opts.positional.isEmpty()) throw UsageException("photocheck needs at least one photo or folder")
    val photos = opts.positional.map(::File).flatMap { f ->
        if (f.isDirectory) f.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }.sortedBy { it.path }.toList() else listOf(f)
    }
    OpenCvNatives.load()
    out("photo,edge_blur_mm,least_px_per_mm,tilt_ratio,even_light,glare,flags")
    val box = ViewBox.DEFAULT
    for (p in photos) {
        val photo = Imgcodecs.imread(p.path, Imgcodecs.IMREAD_COLOR)
        if (photo.empty()) { out("${p.path},,,,,,unreadable"); continue }
        val found = CardFinder.find(photo)
        if (found == null) { out("${p.path},,,,,,card not found"); photo.release(); continue }
        found.edges.view.image.release(); found.edges.paperMask.release()
        val fit = found.fit
        val w = fit.paperWidthMm; val h = fit.paperHeightMm
        val outline = fit.paperOutlineMm?.takeIf { w != null && h != null && abs(w - CardGeometry.PAPER_WIDTH_MM) <= 15 && abs(h - CardGeometry.PAPER_HEIGHT_MM) <= 15 }
        val zones = if (outline != null) CardZones(box, outline) else CardZones(box, CardZones.nominalPaper(), CardZones.NOMINAL_EDGE_MM)
        val view = box.warp(photo, fit.cardToPhoto)
        val q = PhotoCheck.measure(photo, fit.cardToPhoto, view.image, zones)
        val flags = PhotoCheck.flags(q).joinToString(" ") { it.code.name }
        out("%s,%.3f,%.2f,%.3f,%.3f,%.4f,%s".format(p.path, q.edgeBlurMm, q.leastPxPerMm, q.tiltRatio, q.evenLight, q.glare, flags))
        view.image.release(); zones.release(); photo.release()
    }
    return 0
}
