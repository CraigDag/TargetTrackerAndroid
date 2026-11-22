package app.rangetarget.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.core.content.FileProvider
import app.rangetarget.data.Store
import app.rangetarget.data.StoredSession
import rangetarget.core.geometry.PointMm
import rangetarget.core.session.ViewBox
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Sharing a session (picture + CSV) and backing up everything (docs/SPEC.md, App v1, Export). */
object Export {
    fun share(ctx: Context, files: List<File>, mime: String) {
        val uris = files.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it) }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.type = mime
        send.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun outDir(ctx: Context) = File(ctx.cacheDir, "share").apply { mkdirs() }
    private fun baseName(s: StoredSession) = "range-session-" + java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date(s.createdAt))

    /** The card from the last round with every round's groups drawn on, and a short summary above. */
    fun sessionPicture(ctx: Context, store: Store, s: StoredSession): File {
        val dir = store.sessionDir(s.id)
        val last = s.rounds.maxByOrNull { it.number }
        val viewFile = last?.let { File(dir, it.view) } ?: s.baselineView?.let { File(dir, it) }
        val box = ViewBox.DEFAULT
        val card = viewFile?.let { BitmapFactory.decodeFile(it.path) } ?: Bitmap.createBitmap(box.widthPx, box.heightPx, Bitmap.Config.ARGB_8888)
        val lines = ArrayList<String>()
        lines += "${s.drill} · ${dateText(s.createdAt)} · ${metres(s.distanceM)}" + (store.gun(s.gunId)?.let { " · ${it.name}" } ?: "")
        s.averageSize()?.let { lines += "Average group size ${mm(it.first)} (${mrad(it.second)})" }
        for (r in s.rounds.sortedBy { it.number }) for (g in s.groups(r).groups) {
            val m = g.metrics ?: continue
            lines += "Round ${r.number} group ${g.loopId} (${aimName(g.aimPointNumber)}): ${g.detectedShots} shots, " +
                (if (m.shots >= 2) "size ${mm(m.extremeSpreadMm)}, " else "") + (m.offsetWords ?: "")
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 42f; color = android.graphics.Color.WHITE }
        val lineH = 56f
        val header = (lines.size * lineH + 40).toInt()
        val out = Bitmap.createBitmap(card.width, card.height + header, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.rgb(16, 20, 23))
        lines.forEachIndexed { i, l -> c.drawText(l, 30f, 60f + i * lineH, text) }
        c.drawBitmap(card, 0f, header.toFloat(), null)
        val k = card.width.toFloat() / box.widthPx
        fun x(p: PointMm) = ((p.x - box.left) * box.pxPerMm).toFloat() * k
        fun y(p: PointMm) = ((box.top - p.y) * box.pxPerMm).toFloat() * k + header
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5f }
        for (r in s.rounds) {
            val out2 = s.groups(r)
            val col = out2.groups.associate { it.id to android.graphics.Color.parseColor(it.colourRgb) }
            for (l in r.marking.loops) {
                if (l.outlineMm.size < 3) continue
                stroke.color = android.graphics.Color.parseColor(l.colourRgb)
                val p = Path().apply { l.outlineMm.forEachIndexed { i, q -> if (i == 0) moveTo(x(q), y(q)) else lineTo(x(q), y(q)) }; close() }
                c.drawPath(p, stroke)
            }
            for (sh in out2.shots) {
                stroke.color = sh.groupId?.let { col[it] } ?: android.graphics.Color.rgb(255, 82, 82)
                c.drawCircle(x(sh.centreMm), y(sh.centreMm), 5.5f * box.pxPerMm.toFloat() * k, stroke)
            }
        }
        val f = File(outDir(ctx), baseName(s) + ".jpg")
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        card.recycle(); out.recycle()
        return f
    }

    /** One row per shot, with its group's numbers repeated on each row. */
    fun csv(store: Store, sessions: List<StoredSession>): String = buildString {
        appendLine("session,date,drill,added_by_hand,round,distance_m,gun,ammo,group,aim_point,group_shots,extreme_spread_mm,extreme_spread_mrad,mean_radius_mm,offset_x_mm,offset_y_mm,shot,x_mm,y_mm,score")
        for (s in sessions) for (r in s.rounds.sortedBy { it.number }) {
            val out = s.groups(r)
            val byId = out.groups.associateBy { it.id }
            for (sh in out.shots) {
                val g = sh.groupId?.let { byId[it] }; val m = g?.metrics
                val cells = listOf(s.id, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(r.takenAt)), s.drill, s.manual.toString(),
                    r.number.toString(), "%.1f".format(java.util.Locale.US, r.distanceM), store.gun(r.gunId)?.name ?: "", store.ammo(r.ammoId)?.name ?: "",
                    g?.loopId?.toString() ?: "", g?.aimPointNumber?.toString() ?: "", m?.shots?.toString() ?: "",
                    m?.let { "%.1f".format(java.util.Locale.US, it.extremeSpreadMm) } ?: "", m?.let { "%.2f".format(java.util.Locale.US, it.extremeSpreadMrad) } ?: "",
                    m?.let { "%.1f".format(java.util.Locale.US, it.meanRadiusMm) } ?: "",
                    m?.offsetFromAimMm?.let { "%.1f".format(java.util.Locale.US, it.x) } ?: "", m?.offsetFromAimMm?.let { "%.1f".format(java.util.Locale.US, it.y) } ?: "",
                    sh.id, "%.1f".format(java.util.Locale.US, sh.centreMm.x), "%.1f".format(java.util.Locale.US, sh.centreMm.y), sh.score.toString())
                appendLine(cells.joinToString(",") { v -> if (v.any { it == ',' || it == '"' }) "\"" + v.replace("\"", "\"\"") + "\"" else v })
            }
        }
    }

    fun sessionCsv(ctx: Context, store: Store, s: StoredSession): File =
        File(outDir(ctx), baseName(s) + ".csv").apply { writeText(csv(store, listOf(s))) }

    /** Every session (photos and data), kit and settings, plus one CSV of every shot. */
    fun everything(ctx: Context, store: Store): File {
        val f = File(outDir(ctx), "range-target-backup-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".zip")
        ZipOutputStream(f.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("all-shots.csv")); zip.write(csv(store, store.sessions.value).toByteArray()); zip.closeEntry()
            val root = store.dataRoot
            root.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.forEach { file ->
                zip.putNextEntry(ZipEntry(file.relativeTo(root).path))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return f
    }
}
