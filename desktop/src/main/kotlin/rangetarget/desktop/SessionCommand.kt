package rangetarget.desktop

import org.opencv.imgcodecs.Imgcodecs
import rangetarget.core.RangeJson
import rangetarget.core.groundtruth.GroundTruth
import rangetarget.core.groundtruth.TruthCheck
import rangetarget.core.session.SessionAnalyzer
import rangetarget.core.session.SessionPlan
import rangetarget.core.session.SessionResult
import java.io.File

private val IMAGE_EXT = setOf("jpg", "jpeg", "png")

/**
 * `rangetarget session <folder>`: runs the whole pipeline over a session
 * folder (baseline.jpg, round_01.jpg, ...). See docs/session-files.md.
 */
internal fun session(args: List<String>, out: (String) -> Unit): Int {
    val opts = Options.parse(args, valued = setOf("--out", "--plan", "--rounds"), flags = setOf("--no-truth"))
    val folder = File(opts.positional.singleOrNull() ?: throw UsageException("session needs exactly one folder"))
    if (!folder.isDirectory) throw UsageException("No such folder: $folder")
    val photos = folder.listFiles { f -> f.extension.lowercase() in IMAGE_EXT }.orEmpty().sortedBy { it.name }
    val baseline = photos.firstOrNull { it.nameWithoutExtension.lowercase() == "baseline" }
        ?: throw UsageException("No baseline photo (baseline.jpg) in $folder")
    val rounds = photos.mapNotNull { f -> Regex("round_?(\\d+)").matchEntire(f.nameWithoutExtension.lowercase())?.let { it.groupValues[1].toInt() to f } }
        .sortedBy { it.first }
        .filter { (n, _) -> opts.values["--rounds"]?.toIntOrNull()?.let { n <= it } ?: true }
    if (rounds.isEmpty()) throw UsageException("No round photos (round_01.jpg, ...) in $folder")
    val outDir = File(opts.values["--out"] ?: "out/session/${folder.name}")

    // Ground truth next to the photos (synthetic sessions only).
    val truths = if ("--no-truth" in opts.flags) emptyMap() else photos.mapNotNull { p ->
        File(p.parentFile, p.nameWithoutExtension + ".json").takeIf { it.isFile }?.let { runCatching { GroundTruth.read(it) }.getOrNull() }
    }.associateBy { it.round }
    val planFile = opts.values["--plan"]?.let { File(it) } ?: File(folder, "plan.json").takeIf { it.isFile }
    val plan: SessionPlan? = when {
        planFile != null -> RangeJson.decodeFromString(SessionPlan.serializer(), planFile.readText())
        truths.isNotEmpty() -> TruthCheck.planFrom(truths.values.sortedBy { it.round })
        else -> null
    }
    out("Session ${folder.name}: baseline + ${rounds.size} rounds. Plan: ${plan?.source?.ifEmpty { planFile?.name } ?: "none (distances assumed 12 m, counts not checked)"}")

    OpenCvNatives.load()
    val sink = FileSink(outDir) { }
    val analyzer = SessionAnalyzer(plan, sink)
    fun read(f: File) = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_COLOR).also { if (it.empty()) throw UsageException("OpenCV could not read $f") }

    val t0 = System.nanoTime()
    read(baseline).let { img -> val b = analyzer.baseline(img, baseline.name); img.release()
        out("  baseline: ${b.aimPoints.size} aim points (numbers ${b.aimPoints.map { it.number ?: "?" }}), ${b.existingHoles} holes already there, ${b.flags.size} flags") }
    for ((n, f) in rounds) {
        val img = read(f)
        val r = analyzer.round(img, f.name, n)
        img.release()
        out("  round $n: ${r.shots.size} new holes, ${r.groups.size} groups, ${r.shots.count { it.groupId == null }} unassigned, ${r.flags.size + r.groups.sumOf { it.flags.size }} flags" +
            (r.alignment?.let { ", aligned to %.2f mm (95%%)".format(it.leftoverP95Mm) } ?: ""))
    }
    val result = analyzer.result()
    analyzer.release()
    out("  took %.1f s".format((System.nanoTime() - t0) / 1e9))

    File(outDir, "session.json").writeText(RangeJson.encodeToString(SessionResult.serializer(), result) + "\n")
    File(outDir, "shots.csv").writeText(shotsCsv(result))
    File(outDir, "groups.csv").writeText(groupsCsv(result))
    out("  wrote ${outDir.path}/ (session.json, shots.csv, groups.csv, rNN_*.jpg)")

    if (truths.isNotEmpty()) {
        val report = truthReport(result, truths)
        File(outDir, "truth_check.txt").writeText(report)
        out(report.trimEnd())
    }
    return 0
}

fun shotsCsv(r: SessionResult): String = buildString {
    appendLine("round,shot,group,aim_point,x_mm,y_mm,from_aim_x_mm,from_aim_y_mm,score,source,confidence,flags")
    for (round in r.rounds) for (s in round.shots) {
        val g = round.groups.firstOrNull { it.id == s.groupId }
        appendLine(listOf(s.round, s.id, s.groupId ?: "", g?.aimPointNumber ?: "", f1(s.centreMm.x), f1(s.centreMm.y),
            s.fromAimMm?.let { f1(it.x) } ?: "", s.fromAimMm?.let { f1(it.y) } ?: "", s.score, s.source.name.lowercase(),
            "%.2f".format(s.confidence), s.flags.joinToString(";")).joinToString(","))
    }
}

fun groupsCsv(r: SessionResult): String = buildString {
    appendLine("round,group,colour,aim_point,distance_m,fired,found,score,extreme_spread_mm,mean_radius_mm,h_spread_mm,v_spread_mm," +
        "offset_x_mm,offset_y_mm,offset,extreme_spread_mrad,mean_radius_mrad,flags")
    for (round in r.rounds) for (g in round.groups) {
        val m = g.metrics
        appendLine(listOf(g.round, g.id, g.colourName, g.aimPointNumber ?: "", round.distanceM, g.firedShots ?: g.expectedShots ?: "", g.detectedShots,
            m?.totalScore ?: "", m?.let { f1(it.extremeSpreadMm) } ?: "", m?.let { f1(it.meanRadiusMm) } ?: "",
            m?.let { f1(it.horizontalSpreadMm) } ?: "", m?.let { f1(it.verticalSpreadMm) } ?: "",
            m?.offsetFromAimMm?.let { f1(it.x) } ?: "", m?.offsetFromAimMm?.let { f1(it.y) } ?: "", m?.offsetWords ?: "",
            m?.let { "%.2f".format(it.extremeSpreadMrad) } ?: "", m?.let { "%.2f".format(it.meanRadiusMrad) } ?: "",
            g.flags.joinToString(";") { it.code.name }).joinToString(","))
    }
}

private fun f1(v: Double) = "%.1f".format(v)

/** Plain summary of how the result compares with the known answers. */
fun truthReport(r: SessionResult, truths: Map<Int, GroundTruth>): String = buildString {
    appendLine("Compared with the known answers (ground truth):")
    truths[0]?.let { gt ->
        val a = TruthCheck.aimPoints(r.baseline.aimPoints, gt)
        appendLine("  aim points: found ${a.matched} of ${a.trueCount} (+${a.found - a.matched} extra), centre error %s typical / %s worst, numbers right ${a.numbersRight} of ${a.trueCount}"
            .format(mm(a.medianErrorMm), mm(a.maxErrorMm)))
    }
    var tv = 0; var tm = 0; var tf = 0; var ta = 0
    for (round in r.rounds) {
        val gt = truths[round.round] ?: continue
        val c = TruthCheck.round(round, gt)
        tv += c.trueVisible; tm += c.matched; tf += c.falseHoles.size; ta += c.assignedRight
        appendLine("  round ${c.round}: found ${c.matched} of ${c.trueVisible} visible holes (${c.trueShots - c.trueVisible} hidden), ${c.falseHoles.size} false; " +
            "position error %s typical / %s (95%%) / %s worst; right group ${c.assignedRight} of ${c.matched}".format(mm(c.medianErrorMm), mm(c.p95ErrorMm), mm(c.maxErrorMm)))
        if (c.missed.isNotEmpty()) appendLine("      missed: ${c.missed.joinToString()}")
        if (c.falseHoles.isNotEmpty()) appendLine("      false: ${c.falseHoles.joinToString { "(%.0f, %.0f)".format(it.x, it.y) }}")
        if (c.assignedWrong.isNotEmpty()) appendLine("      wrong group: ${c.assignedWrong.joinToString()}")
    }
    appendLine("  overall: %.1f%% of visible holes found, %d false, %.1f%% of found holes in the right group".format(
        100.0 * tm / tv.coerceAtLeast(1), tf, 100.0 * ta / tm.coerceAtLeast(1)))
}

private fun mm(v: Double?) = v?.let { "%.1f mm".format(it) } ?: "-"
