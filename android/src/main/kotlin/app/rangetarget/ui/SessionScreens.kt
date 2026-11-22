@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.rangetarget.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.rangetarget.RangeApp
import app.rangetarget.data.DRILLS
import app.rangetarget.data.StoredSession
import rangetarget.core.flags.Flag
import java.io.File

// ---------------------------------------------------------------- plan

@Composable
fun PlanScreen(app: RangeApp, nav: NavHostController) {
    val settings by app.store.settings.collectAsState()
    val kit by app.store.kit.collectAsState()
    var drill by remember { mutableStateOf(DRILLS[0]) }
    var groups by remember { mutableStateOf(DRILLS[0].groups.toString()) }
    var shots by remember { mutableStateOf(DRILLS[0].shots?.toString() ?: "") }
    var distance by remember { mutableStateOf(settings.defaultDistanceM) }
    var gunId by remember { mutableStateOf(kit.guns.firstOrNull()?.id) }
    var ammoId by remember { mutableStateOf(kit.ammo.firstOrNull()?.id) }

    Screen("Quick plan", onBack = { nav.popBackStack() }, bottom = {
        MainButton("Next: photo of the card") {
            val id = app.store.newId()
            app.store.saveSession(StoredSession(
                id = id, createdAt = System.currentTimeMillis(), drill = drill.name,
                groupsPerRound = groups.toIntOrNull()?.coerceIn(1, 12) ?: drill.groups,
                shotsPerGroup = shots.toIntOrNull()?.coerceIn(1, 60),
                distanceM = distance, gunId = gunId, ammoId = ammoId,
            ))
            nav.navigate("baseline/$id") { popUpTo("home") }
        }
    }) {
        Label("Drill")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DRILLS.forEach { d -> FilterChip(d == drill, { drill = d; groups = d.groups.toString(); shots = d.shots?.toString() ?: "" }, { Text(d.name) }) }
        }
        Muted(drill.about)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(groups, { groups = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Groups per round") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(shots, { shots = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("Shots per group") }, singleLine = true,
                placeholder = { Text("any") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        DistancePicker(distance) { distance = it }
        KitPicker("Gun", kit.guns.map { it.id to it.name }, gunId) { gunId = it }
        KitPicker("Ammo", kit.ammo.map { it.id to it.name }, ammoId) { ammoId = it }
        if (kit.guns.isEmpty() && kit.ammo.isEmpty()) Muted("Add guns and ammo under Menu → My kit to pick them here.")
    }
}

@Composable
fun DistancePicker(value: Double, onChange: (Double) -> Unit) {
    var custom by remember { mutableStateOf(if (value != 12.0 && value != 25.0) value.toString() else "") }
    Label("Distance")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(value == 12.0, { onChange(12.0); custom = "" }, { Text("12 m") })
        FilterChip(value == 25.0, { onChange(25.0); custom = "" }, { Text("25 m") })
        OutlinedTextField(custom, { t -> custom = t.filter { it.isDigit() || it == '.' }; custom.toDoubleOrNull()?.takeIf { it in 1.0..100.0 }?.let(onChange) },
            Modifier.width(130.dp), label = { Text("Other (m)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
}

@Composable
fun KitPicker(label: String, items: List<Pair<String, String>>, selected: String?, onPick: (String?) -> Unit) {
    if (items.isEmpty()) return
    Label(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected == null, { onPick(null) }, { Text("Not set") })
        items.forEach { (id, name) -> FilterChip(selected == id, { onPick(id) }, { Text(name) }) }
    }
}

// ---------------------------------------------------------------- photos

/** Take or pick a photo, then run [analyse] on it in the background; [onDone] when it worked. */
@Composable
fun PhotoStep(app: RangeApp, workKey: String, save: (File) -> Unit, analyse: suspend () -> Unit, onDone: () -> Unit) {
    val work by app.engine.work.collectAsState()
    var message by remember { mutableStateOf<String?>(null) }
    val mine = work?.takeIf { it.key == workKey }
    LaunchedEffect(mine) {
        if (mine != null && !mine.running && mine.error == null) { app.engine.clearWork(); onDone() }
    }
    val source = rememberPhotoSource(onPhoto = { f ->
        message = null
        if (work?.running == true) { message = "Still reading another photo; try again in a moment."; return@rememberPhotoSource }
        save(f)
        app.engine.start(workKey) { analyse() }
    }, onError = { message = it })

    when {
        mine?.running == true -> Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Column {
                    Text("Reading the card…", fontWeight = FontWeight.SemiBold)
                    Muted("This can take up to a minute. You can leave the app; it carries on.")
                }
            }
        }
        mine?.error != null -> Panel(warn = true) { Text(mine.error) }
        else -> {}
    }
    message?.let { Panel(warn = true) { Text(it) } }
    val busy = mine?.running == true
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MainButton("Take photo", enabled = !busy) { source.takePhoto() }
        SecondButton("From gallery", enabled = !busy) { source.pickPhoto() }
    }
    Muted("Tips: whole card in the frame, phone square on to the card, no glare or strong shadows.")
}

@Composable
fun BaselineScreen(app: RangeApp, nav: NavHostController, sid: String) {
    Screen("Blank card photo", onBack = { nav.popBackStack() }) {
        Panel {
            Text("Photograph the card before the first shot.", fontWeight = FontWeight.SemiBold)
            Muted("Put any aim-point stickers or drawn circles on first. The card can already have holes; only new holes count.")
        }
        PhotoStep(app, "b/$sid",
            save = { f -> f.copyTo(File(app.store.sessionDir(sid), "baseline.jpg"), overwrite = true); app.store.update(sid) { it.copy(baselinePhoto = "baseline.jpg") } },
            analyse = { app.engine.baseline(sid) },
            onDone = { nav.navigate("cardcheck/$sid") },
        )
    }
}

@Composable
fun FlagList(flags: List<Flag>) {
    if (flags.isEmpty()) return
    Panel(warn = true) {
        Label("Check")
        flags.distinctBy { it.reason }.take(6).forEach { Text("• " + it.reason, fontSize = 14.sp) }
    }
}

@Composable
fun CardCheckScreen(app: RangeApp, nav: NavHostController, sid: String) {
    val sessions by app.store.sessions.collectAsState()
    val s = sessions.firstOrNull { it.id == sid } ?: return
    val dir = app.store.sessionDir(sid)
    Screen("Check the card", onBack = { nav.popBackStack() }, bottom = {
        MainButton("Start round 1") { nav.navigate("round/$sid/1") }
    }) {
        CardCanvas(File(dir, s.baselineView ?: "v00.jpg"), s.aims, emptyList(), emptyList(),
            onTap = { p -> app.store.update(sid) { it.copy(aims = toggleAim(it.aims, p)) } })
        val n = s.aims.count { it.number > 0 }
        Text(if (n == 0) "Card found. No aim points yet." else "Card found, with $n aim point${if (n == 1) "" else "s"}.", fontWeight = FontWeight.SemiBold)
        Muted("The card centre is always an aim point. Tap the card where you'll aim to add one; tap one to remove it. They're numbered in the order you add them, so shoot your groups in that order.")
        FlagList(s.baselineFlags)
    }
}

/** Tap near an aim point (not the centre) to remove it, elsewhere to add the next number. */
fun toggleAim(aims: List<rangetarget.core.session.AimPoint>, p: rangetarget.core.geometry.PointMm): List<rangetarget.core.session.AimPoint> {
    val hit = aims.filter { it.number > 0 }.minByOrNull { (it.centreMm - p).radius }?.takeIf { (it.centreMm - p).radius < 15.0 }
    val others = if (hit != null) aims.filter { it !== hit } else aims + rangetarget.core.session.AimPoint((aims.maxOfOrNull { it.number } ?: 0) + 1, p, "tapped")
    var k = 0
    return others.sortedBy { it.number }.map { if (it.number == 0) it else it.copy(number = ++k) }
}

@Composable
fun RoundPhotoScreen(app: RangeApp, nav: NavHostController, sid: String, n: Int) {
    val sessions by app.store.sessions.collectAsState()
    val kit by app.store.kit.collectAsState()
    val s = sessions.firstOrNull { it.id == sid } ?: return
    val prev = s.rounds.filter { it.number < n }.maxByOrNull { it.number }
    var distance by remember { mutableStateOf(prev?.distanceM ?: s.distanceM) }
    var gunId by remember { mutableStateOf(prev?.gunId ?: s.gunId) }
    var ammoId by remember { mutableStateOf(prev?.ammoId ?: s.ammoId) }
    val aims = s.aims.count { it.number > 0 }
    Screen("Round $n", onBack = { nav.popBackStack() }, bottom = if (n > 1) ({
        SecondButton("Finish session") { app.store.update(sid) { it.copy(finished = true) }; nav.navigate("summary/$sid") { popUpTo("home") } }
    }) else null) {
        Panel {
            Text("Shoot ${s.groupsPerRound} group${if (s.groupsPerRound == 1) "" else "s"}${s.shotsPerGroup?.let { " of $it" } ?: ""}" +
                (if (aims > 0) ", in aim-point order (1 to $aims)." else " at the card centre or your chosen spots."), fontWeight = FontWeight.SemiBold)
            Muted("Then walk down and photograph the card the same way as the blank one. You'll circle the groups on the phone.")
        }
        DistancePicker(distance) { distance = it }
        KitPicker("Gun", kit.guns.map { it.id to it.name }, gunId) { gunId = it }
        KitPicker("Ammo", kit.ammo.map { it.id to it.name }, ammoId) { ammoId = it }
        val name = "r%02d.jpg".format(n)
        PhotoStep(app, "r/$sid/$n",
            save = { f -> f.copyTo(File(app.store.sessionDir(sid), name), overwrite = true) },
            analyse = { app.engine.round(sid, n, name, distance, gunId, ammoId) },
            onDone = { nav.navigate("circle/$sid/$n") },
        )
        if (s.rounds.any { it.number >= n }) Muted("A photo for this round is already saved. A new one replaces it (and any later rounds).")
    }
}

@Composable
fun ColourDot(c: Color) = Box(Modifier.size(14.dp).background(c, CircleShape))
