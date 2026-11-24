@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.rangetarget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import app.rangetarget.RangeApp
import app.rangetarget.data.StoredSession
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.geometry.PointMm
import rangetarget.core.session.PatternGroup
import rangetarget.core.session.Patterns
import kotlin.math.abs
import kotlin.math.max

/** One measured group with the kit it was shot with, for patterns and comparisons. */
data class KitGroup(val group: PatternGroup, val gunId: String?, val ammoId: String?, val sessionId: String)

/** Every circled group in [s] with 2+ shots, with its shots, aim point and distance. */
fun kitGroups(s: StoredSession): List<KitGroup> = s.rounds.flatMap { r ->
    val out = s.groups(r)
    val byId = out.shots.associateBy { it.id }
    val aimAt = s.aims.associate { it.number to it.centreMm }
    out.groups.mapNotNull { g ->
        val shots = g.shotIds.mapNotNull { byId[it]?.centreMm }
        if (shots.size < 2) null
        else KitGroup(PatternGroup(shots, g.aimPointNumber?.let { aimAt[it] }, r.distanceM), r.gunId ?: s.gunId, r.ammoId ?: s.ammoId, s.id)
    }
}

/**
 * Patterns across groups (docs/SPEC.md, Patterns): where shots land around each
 * group's middle, where group middles land around their aim points, and plain
 * notes with a question each. [sid] starts on that session; null starts on all.
 */
@Composable
fun PatternsScreen(app: RangeApp, nav: NavHostController, sid: String?) {
    val sessions by app.store.sessions.collectAsState()
    val settings by app.store.settings.collectAsState()
    val kit by app.store.kit.collectAsState()
    var scope by remember { mutableStateOf(if (sid != null) "this" else "all") }
    var gunId by remember { mutableStateOf<String?>(null) }
    val all = remember(sessions) { sessions.flatMap { kitGroups(it) } }
    val chosen = when (scope) {
        "this" -> all.filter { it.sessionId == sid }
        "last5" -> sessions.take(5).map { it.id }.toSet().let { ids -> all.filter { it.sessionId in ids } }
        else -> all
    }.filter { gunId == null || it.gunId == gunId }
    val ref = settings.defaultDistanceM
    val p = remember(chosen, ref) { Patterns(chosen.map { it.group }, ref) }

    Screen("Patterns", onBack = { nav.popBackStack() }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sid != null) FilterChip(scope == "this", { scope = "this" }, { Text("This session") })
            FilterChip(scope == "last5", { scope = "last5" }, { Text("Last 5 sessions") })
            FilterChip(scope == "all", { scope = "all" }, { Text("All sessions") })
        }
        val gunsUsed = kit.guns.filter { g -> all.any { it.gunId == g.id } }
        if (gunsUsed.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(gunId == null, { gunId = null }, { Text("Any gun") })
            gunsUsed.forEach { g -> FilterChip(gunId == g.id, { gunId = g.id }, { Text(g.name) }) }
        }
        Muted("${p.shotCount} shots in ${p.groups.size} groups. Sizes are shown as they would be at ${metres(ref)}, so groups shot at other distances compare fairly.")

        Panel {
            Label("Shots around each group's middle")
            ShotPlot(p.aroundMiddle, null, Modifier.fillMaxWidth().aspectRatio(1f))
            Muted("Every group laid on top of the others, middle to middle. The shape shows how your shots spread. The circle is the 10 ring for scale.")
        }
        if (p.middlesFromAim.isNotEmpty()) Panel {
            Label("Group middles around the aim point")
            ShotPlot(p.middlesFromAim, p.averageOffset, Modifier.fillMaxWidth().aspectRatio(1f))
            Muted("Each dot is one group's middle; the cross is the aim point and the ring is their average. The circle is the 10 ring for scale.")
        }
        p.notes().forEach { n ->
            Panel {
                Text(n.title, fontWeight = FontWeight.SemiBold)
                Text(n.text)
                Text(n.question, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Dots in mm about a cross at the middle, with the 10 ring drawn for scale and an optional average ring. */
@Composable
fun ShotPlot(points: List<PointMm>, average: PointMm?, modifier: Modifier) {
    val dot = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    val mark = MaterialTheme.colorScheme.tertiary
    val ten = CardGeometry.ringDiameterMm(10) / 2
    Canvas(modifier) {
        val reach = max(ten * 1.4, (points.maxOfOrNull { max(abs(it.x), abs(it.y)) } ?: 0.0) * 1.15)
        val k = (size.minDimension / 2 / reach).toFloat()
        val c = Offset(size.width / 2, size.height / 2)
        fun at(p: PointMm) = Offset(c.x + p.x.toFloat() * k, c.y - p.y.toFloat() * k)
        drawCircle(grid, (ten * k).toFloat(), c, style = Stroke(1.dp.toPx()))
        drawLine(grid, Offset(0f, c.y), Offset(size.width, c.y), 1.dp.toPx())
        drawLine(grid, Offset(c.x, 0f), Offset(c.x, size.height), 1.dp.toPx())
        points.forEach { drawCircle(dot.copy(alpha = 0.55f), 4.dp.toPx(), at(it)) }
        average?.let { drawCircle(mark, 8.dp.toPx(), at(it), style = Stroke(2.5.dp.toPx())) }
        val s = 6.dp.toPx()
        drawLine(Color.Red, Offset(c.x - s, c.y), Offset(c.x + s, c.y), 2.dp.toPx())
        drawLine(Color.Red, Offset(c.x, c.y - s), Offset(c.x, c.y + s), 2.dp.toPx())
    }
}

/** Average group size per gun (or ammo), mm at the reference distance, for the stats screen. */
@Composable
fun KitComparison(title: String, rows: List<Pair<String, List<PatternGroup>>>, refM: Double) {
    val shown = rows.filter { it.second.isNotEmpty() }
    if (shown.size < 2) return
    Panel {
        Label(title)
        val sizes = shown.map { (name, gs) -> Triple(name, gs.size, gs.map { g -> spreadMm(g) * refM / g.distanceM }.average()) }
        val best = sizes.maxOf { it.third }
        sizes.sortedBy { it.third }.forEach { (name, n, size) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.SemiBold)
                    Muted("$n group${if (n == 1) "" else "s"}${if (n < 5) " · too few to tell yet" else ""}")
                }
                Text(mm(size), fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(progress = { (size / best).toFloat() }, Modifier.fillMaxWidth().height(6.dp))
        }
        Muted("Average group size (widest two shots) as at ${metres(refM)}. Smaller is tighter. Many things change between sessions, so treat a gap as a question, not an answer.")
    }
}

private fun spreadMm(g: PatternGroup): Double {
    var es = 0.0
    for (i in g.shotsMm.indices) for (j in i + 1 until g.shotsMm.size) es = max(es, (g.shotsMm[i] - g.shotsMm[j]).radius)
    return es
}
