@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.rangetarget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.rangetarget.RangeApp
import app.rangetarget.data.Edit
import app.rangetarget.data.LOOP_COLOURS
import app.rangetarget.data.Marking
import app.rangetarget.data.StoredRound
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rangetarget.core.flags.FlagCode
import rangetarget.core.geometry.CardGeometry
import rangetarget.core.session.Calls
import rangetarget.core.session.DrawnGroups
import rangetarget.core.session.DrawnLoop
import rangetarget.core.session.GroupReading
import rangetarget.core.session.Observations
import rangetarget.core.session.ShotReading
import rangetarget.core.session.ShotSource
import java.io.File

private enum class Tool(val label: String) { CIRCLE("Circle a group"), AIM("Add aim point"), REMOVE("Not a hole"), ADD("Add missed hole") }

// ---------------------------------------------------------------- circle groups

@Composable
fun CircleScreen(app: RangeApp, nav: NavHostController, sid: String, n: Int, from: String) {
    val sessions by app.store.sessions.collectAsState()
    val s = sessions.firstOrNull { it.id == sid } ?: return
    val r = s.round(n) ?: return
    var marking by remember(sid, n) { mutableStateOf(r.marking) }
    var tool by remember { mutableStateOf(Tool.CIRCLE) }
    var note by remember { mutableStateOf<String?>(null) }
    val out = DrawnGroups.measure(n, r.effectiveShots(marking), marking.loops, s.aims, r.distanceM, s.shotsPerGroup)
    val colourOf = out.groups.associate { g -> g.id to hexColour(g.colourRgb) }
    // Groups short of holes: where the missing shot may be hidden (an older hole, or a big hole that may be two).
    val hintsFor = out.groups.mapIndexedNotNull { i, g ->
        val want = g.expectedShots ?: return@mapIndexedNotNull null
        if (g.detectedShots >= want) return@mapIndexedNotNull null
        val inside = r.hints.filter { h -> DrawnGroups.inside(h.centreMm, marking.loops[i].outlineMm) &&
            marking.addedShots.none { (it.centreMm - h.centreMm).radius < 4 } }
        Triple(i, g, inside)
    }
    val shownHints = hintsFor.flatMap { it.third }

    fun nextColour(): Pair<String, String> {
        val used = marking.loops.map { it.colourName }.toSet()
        return LOOP_COLOURS.firstOrNull { it.first !in used } ?: LOOP_COLOURS[marking.loops.size % LOOP_COLOURS.size]
    }

    Screen(if (from == "session") "Circle your groups" else "Markup · round $n", onBack = { nav.popBackStack() }, bottom = {
        SecondButton("Undo", enabled = marking.loops.isNotEmpty()) { marking = marking.copy(loops = marking.loops.dropLast(1)) }
        MainButton("Save groups") {
            app.store.update(sid) { ss ->
                ss.copy(rounds = ss.rounds.map { x -> if (x.number != n) x else saved(x, marking) })
            }
            nav.navigate("results/$sid/$n?from=$from") { popUpTo("circle/{sid}/{n}?from={from}") { inclusive = true } }
        }
    }) {
        if (from == "session" && r.original == null) PhotoAdvice(r.flags) {
            if (!nav.popBackStack()) nav.navigate("round/$sid/$n")
        }
        CardCanvas(
            File(app.store.sessionDir(sid), r.view), s.aims,
            out.shots.map { ShotMark(it.centreMm, it.groupId?.let(colourOf::get), it.flags.any { f -> f != FlagCode.HOLE_OUTSIDE_LOOPS }) },
            marking.loops.mapIndexed { i, l -> LoopMark(l.outlineMm, hexColour(l.colourRgb), "${i + 1}") },
            onLoop = if (tool == Tool.CIRCLE) { pts ->
                if (DrawnGroups.perimeterMm(pts) < 25) note = "That loop was too small to hold a group. Draw round the whole group."
                else {
                    val (name, rgb) = nextColour()
                    val aim = DrawnGroups.proposeAim(pts, r.effectiveShots(marking), s.aims)
                    marking = marking.copy(loops = marking.loops + DrawnLoop(pts, aim, name, rgb)); note = null
                }
            } else null,
            hints = shownHints.map { it.centreMm },
            onTap = { p ->
                when (tool) {
                    Tool.CIRCLE -> note = "Draw a loop round each group with one finger. Use two fingers to zoom."
                    Tool.AIM -> app.store.update(sid) { it.copy(aims = toggleAim(it.aims, p)) }
                    Tool.REMOVE -> {
                        val hit = r.effectiveShots(marking).minByOrNull { (it.centreMm - p).radius }?.takeIf { (it.centreMm - p).radius < 8 }
                        if (hit == null) note = "No hole there. Tap right on a red or coloured ring."
                        else marking = if (hit in marking.addedShots) marking.copy(addedShots = marking.addedShots - hit)
                            else marking.copy(removedShotIds = marking.removedShotIds + hit.id)
                    }
                    Tool.ADD -> {
                        // A tap on a dashed ring puts the shot at that hole's middle.
                        val at = shownHints.map { it.centreMm }.minByOrNull { (it - p).radius }?.takeIf { (it - p).radius < 6 } ?: p
                        val d = r.shots.firstOrNull()?.diameterMm ?: 9.0
                        val id = "r%da%02d".format(n, marking.addedShots.size + 1 + marking.removedShotIds.size)
                        marking = marking.copy(addedShots = marking.addedShots + ShotReading(id, n, null, at, null, d,
                            CardGeometry.scoreByEdge(at, d), ShotSource.CLEAN, 1.0, emptyList()))
                    }
                }
            },
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool.entries.forEach { t -> FilterChip(tool == t, { tool = t; note = null }, { Text(t.label) }) }
        }
        note?.let { Muted(it) }
        val found = out.shots.size
        Text(when {
            found == 0 -> "No new holes found. If there should be some, use \"Add missed hole\"."
            marking.loops.isEmpty() -> "$found new hole${if (found == 1) "" else "s"} found (red). Draw a loop round each group."
            else -> "$found new holes · ${out.unassigned.size} not in a group"
        }, fontWeight = FontWeight.SemiBold)
        if (out.unassigned.isNotEmpty() && marking.loops.isNotEmpty()) Muted("Holes outside every loop (red) aren't counted. Circle them, or leave them out if they're flyers you don't want measured.")

        out.groups.forEachIndexed { i, g -> GroupEditRow(g, s.aims.map { it.number }, onAim = { a ->
            marking = marking.copy(loops = marking.loops.mapIndexed { j, l -> if (j == i) l.copy(aimPointNumber = a) else l })
        }, onDelete = { marking = marking.copy(loops = marking.loops.filterIndexed { j, _ -> j != i }) }) }
        hintsFor.forEach { (i, g, inside) ->
            val older = inside.count { it.kind == "older" }; val big = inside.count { it.kind == "big" }
            Muted("Group ${i + 1} has ${g.detectedShots} of ${g.expectedShots} shots. " + when {
                inside.isEmpty() -> "The missing shot may have missed the card, or landed outside the loop."
                else -> "A shot can go through an older hole and leave no new mark. The dashed yellow rings inside the loop are " +
                    listOfNotNull(older.takeIf { it > 0 }?.let { "older holes" }, big.takeIf { it > 0 }?.let { "holes big enough to be two shots" }).joinToString(" and ") +
                    ". If you remember where it went, pick \"Add missed hole\" and tap the ring."
            })
        }
        FlagList(r.flags)
        if (from != "session" && r.original != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ marking = r.original }) { Text("Put back to original") }
            if (r.edits.isNotEmpty()) OutlinedButton({ marking = r.edits.dropLast(1).lastOrNull()?.after ?: r.original }) { Text("Undo last edit") }
        }
        if (r.edits.isNotEmpty()) Muted("Edits: " + r.edits.takeLast(5).joinToString(" · ") { "${it.what} (${timeText(it.atMillis)})" })
    }
}

/** Store a new marking, keeping the first save as the original and later ones as edits. */
private fun saved(r: StoredRound, m: Marking): StoredRound {
    if (r.original == null) return r.copy(marking = m, original = m)
    if (m == r.marking) return r
    val what = buildList {
        val dl = m.loops.size - r.marking.loops.size
        if (dl > 0) add("$dl loop${if (dl == 1) "" else "s"} added") else if (dl < 0) add("${-dl} loop${if (dl == -1) "" else "s"} removed")
        else if (m.loops != r.marking.loops) add("loops redrawn")
        if (m.removedShotIds != r.marking.removedShotIds || m.addedShots != r.marking.addedShots) add("holes fixed")
        if (m == r.original) { clear(); add("put back to original") }
    }.joinToString(", ").ifEmpty { "changed" }
    return r.copy(marking = m, edits = r.edits + Edit(System.currentTimeMillis(), what, m))
}

@Composable
private fun GroupEditRow(g: GroupReading, aimNumbers: List<Int>, onAim: (Int?) -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColourDot(hexColour(g.colourRgb))
            Column(Modifier.weight(1f)) {
                Text("Group ${g.loopId} · ${g.detectedShots} hole${if (g.detectedShots == 1) "" else "s"}", fontWeight = FontWeight.SemiBold)
                g.flags.firstOrNull { it.code != FlagCode.LOOP_WITHOUT_AIM_POINT }?.let { Muted(it.reason) }
            }
            Box {
                TextButton({ open = true }) { Text(aimName(g.aimPointNumber) + " ▾") }
                DropdownMenu(open, { open = false }) {
                    aimNumbers.sorted().forEach { a -> DropdownMenuItem({ Text(aimName(a)) }, { onAim(a); open = false }) }
                    DropdownMenuItem({ Text("No aim point") }, { onAim(null); open = false })
                }
            }
            IconButton(onDelete) { Icon(Icons.Filled.Delete, "Remove loop") }
        }
    }
}

// ---------------------------------------------------------------- results

@Composable
fun GroupResult(g: GroupReading, call: Calls.Match? = null) {
    val m = g.metrics
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColourDot(hexColour(g.colourRgb))
            Text("Group ${g.loopId} · ${aimName(g.aimPointNumber)}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Muted("${g.detectedShots} shot${if (g.detectedShots == 1) "" else "s"}")
        }
        if (m == null || m.shots < 2) { Muted(if (m == null) "No holes in this loop." else "One hole: nothing to measure yet."); return@Panel }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Big(mm(m.extremeSpreadMm))
            Muted("group size · ${mrad(m.extremeSpreadMrad)} at ${metres(m.distanceM)}")
        }
        Muted("Average distance from the group's middle: ${mm(m.meanRadiusMm)}. Wide ${mm(m.horizontalSpreadMm)}, tall ${mm(m.verticalSpreadMm)}.")
        m.offsetWords?.let { Text("Group middle: $it of the aim point", color = if (it == "on the aim point") Good else MaterialTheme.colorScheme.onSurface) }
        call?.let { Text("Your call: the group landed ${Calls.words(it)}.", color = MaterialTheme.colorScheme.primary) }
        if (g.aimPointNumber == 0) Text("Score ${m.totalScore} (whole rings, card score at ${metres(m.distanceM)})")
        g.flags.filter { it.code != FlagCode.LOOP_WITHOUT_AIM_POINT }.forEach { Text("• " + it.reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
fun ResultsScreen(app: RangeApp, nav: NavHostController, sid: String, n: Int, from: String) {
    val sessions by app.store.sessions.collectAsState()
    val s = sessions.firstOrNull { it.id == sid } ?: return
    val r = s.round(n) ?: return
    val out = s.groups(r)
    Screen("Round $n results", onBack = { nav.popBackStack() }, actions = {
        IconButton({ nav.navigate("circle/$sid/$n?from=${if (from == "session") "fix" else from}") }) { Icon(Icons.Filled.Edit, "Fix groups") }
    }, bottom = {
        when (from) {
            "session" -> {
                SecondButton("Finish") { app.store.update(sid) { it.copy(finished = true) }; nav.navigate("summary/$sid") { popUpTo("home") } }
                MainButton("Next round") { nav.navigate("round/$sid/${n + 1}") }
            }
            "markup" -> MainButton("Done") { nav.navigate("markup") { popUpTo("markup") { inclusive = true } } }
            else -> MainButton("Back") { nav.popBackStack() }
        }
    }) {
        val colourOf = out.groups.associate { it.id to hexColour(it.colourRgb) }
        CardCanvas(File(app.store.sessionDir(sid), r.view), s.aims,
            out.shots.map { ShotMark(it.centreMm, it.groupId?.let(colourOf::get)) },
            r.marking.loops.mapIndexed { i, l -> LoopMark(l.outlineMm, hexColour(l.colourRgb), "${i + 1}") },
            calls = s.callsFor(n))
        Panel { Label("Something to think about"); Text(Observations.round(out.groups), fontSize = 17.sp) }
        val calls = s.callMatches(r).associateBy { it.groupIndex }
        if (s.callsFor(n).isNotEmpty()) Muted("White crosses are your calls. " + when {
            calls.isEmpty() -> "None was near a group (within ${Calls.MAX_MM.toInt()} mm)."
            calls.size < s.callsFor(n).size -> "${s.callsFor(n).size - calls.size} call${if (s.callsFor(n).size - calls.size == 1) " wasn't" else "s weren't"} near any group."
            else -> ""
        })
        out.groups.forEachIndexed { i, g -> GroupResult(g, calls[i]) }
        RoundNote(app, sid, r)
        if (out.unassigned.isNotEmpty()) Muted("${out.unassigned.size} hole${if (out.unassigned.size == 1) "" else "s"} not in any group (not counted).")
        Muted("Photo read in %.1f s on this phone.".format(r.analysisMillis / 1000.0))
    }
}

// ---------------------------------------------------------------- session summary

@Composable
fun SummaryScreen(app: RangeApp, nav: NavHostController, sid: String) {
    val sessions by app.store.sessions.collectAsState()
    val s = sessions.firstOrNull { it.id == sid } ?: return
    val ctx = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (confirmDelete) AlertDialog({ confirmDelete = false },
        confirmButton = { TextButton({ confirmDelete = false; nav.home(); app.store.deleteSession(sid) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } },
        title = { Text("Delete this session?") }, text = { Text("Its photos and results are removed from the phone. This can't be undone.") })

    Screen("Session", onBack = { if (!nav.popBackStack()) nav.home() }, actions = {
        IconButton({
            sharing = true
            scope.launch {
                val files = withContext(Dispatchers.IO) { runCatching { listOf(Export.sessionPicture(ctx, app.store, s), Export.sessionCsv(ctx, app.store, s)) }.getOrNull() }
                sharing = false
                if (files != null) Export.share(ctx, files, "*/*")
            }
        }) { Icon(Icons.Filled.Share, "Share") }
        IconButton({ confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete session") }
    }, bottom = { MainButton("Home") { nav.home() } }) {
        Text("${s.drill}${if (s.manual) " (added by hand)" else ""}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Muted(listOfNotNull(dateText(s.createdAt), metres(s.distanceM), app.store.gun(s.gunId)?.name, app.store.ammo(s.ammoId)?.name).joinToString(" · "))
        if (sharing) Muted("Preparing the picture and spreadsheet…")
        s.averageSize()?.let { (a, b) ->
            Panel { Label("Average group size"); Big(mm(a)); Muted("${mrad(b)} · from ${s.rounds.sumOf { r -> s.groups(r).groups.count { (it.metrics?.shots ?: 0) >= 2 } }} groups") }
        }
        val called = s.rounds.flatMap { s.callMatches(it) }
        if (called.isNotEmpty()) Panel {
            Label("Your calls")
            Big(mm(called.map { it.landedFromCall.radius }.average()))
            Muted("Average gap between where you called ${if (called.size == 1) "the group" else "${called.size} groups"} and where ${if (called.size == 1) "it" else "they"} landed. The smaller it gets, the better you're reading your own shots.")
        }
        if (s.rounds.isNotEmpty()) OutlinedButton({ nav.navigate("patterns?sid=$sid") }, Modifier.fillMaxWidth()) { Text("Patterns in this session") }
        if (s.rounds.isEmpty()) Muted("No rounds yet.")
        s.rounds.sortedBy { it.number }.forEach { r ->
            val g = s.groups(r).groups
            val sizes = g.mapNotNull { it.metrics }.filter { it.shots >= 2 }
            Panel(Modifier.clickable { nav.navigate("results/$sid/${r.number}?from=summary") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Round ${r.number}", fontWeight = FontWeight.SemiBold)
                        Muted("${g.size} group${if (g.size == 1) "" else "s"} · ${metres(r.distanceM)}" +
                            (app.store.gun(r.gunId)?.let { " · ${it.name}" } ?: "") + (if (r.edits.isNotEmpty()) " · edited" else ""))
                    }
                    if (sizes.isNotEmpty()) Text(mm(sizes.map { it.extremeSpreadMm }.average()), fontWeight = FontWeight.SemiBold)
                }
                if (r.note.isNotBlank()) Text("“${r.note.trim()}”", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            }
        }
        if (!s.finished && !s.manual) OutlinedButton({ nav.navigate(resumeRoute(s)) }) { Text("Carry on with this session") }
    }
}

/** The shooter's own note on a round, saved as they type. */
@Composable
private fun RoundNote(app: RangeApp, sid: String, r: StoredRound) {
    var text by remember(sid, r.number) { mutableStateOf(r.note) }
    LaunchedEffect(text) {
        if (text == r.note) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        app.store.update(sid) { ss -> ss.copy(rounds = ss.rounds.map { x -> if (x.number != r.number) x else x.copy(note = text) }) }
    }
    Panel {
        Label("Your notes")
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("What did you feel on the shots? What will you try next round?") })
        Muted("Saved with the round and shown on the session summary.")
    }
}
