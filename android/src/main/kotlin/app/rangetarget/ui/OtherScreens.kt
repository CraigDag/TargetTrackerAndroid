@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.rangetarget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import app.rangetarget.RangeApp
import app.rangetarget.data.Ammo
import app.rangetarget.data.Gun
import app.rangetarget.data.StoredSession
import app.rangetarget.data.TargetItem
import rangetarget.core.RangeTarget
import java.io.File

/** A small line chart of group sizes, oldest on the left. */
@Composable
fun SizeChart(values: List<Double>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val lo = values.min() * 0.9; val hi = values.max() * 1.1
        val span = (hi - lo).takeIf { it > 1e-9 } ?: 1.0
        fun pt(i: Int) = Offset(size.width * i / (values.size - 1), (size.height * (1 - (values[i] - lo) / span)).toFloat())
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        val p = Path().apply { values.indices.forEach { i -> val o = pt(i); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(p, line, style = Stroke(2.5.dp.toPx()))
        values.indices.forEach { drawCircle(line, 3.5.dp.toPx(), pt(it)) }
    }
}

@Composable
fun StatsScreen(app: RangeApp, nav: NavHostController) {
    val sessions by app.store.sessions.collectAsState()
    Screen("Stats", onBack = { nav.popBackStack() }) {
        val sized = sessions.mapNotNull { s -> s.averageSize()?.let { s to it } }.reversed()
        Panel {
            Label("Average group size over time")
            if (sized.size < 2) Muted("Shoot two or more sessions to see a trend.")
            else {
                SizeChart(sized.map { it.second.second }, Modifier.fillMaxWidth().height(140.dp))
                Muted("In mrad, so 12 m and 25 m sessions compare. Smaller is tighter. A few sessions are mostly noise: look for a trend over many.")
            }
        }
        Label("Sessions")
        if (sessions.isEmpty()) Muted("No sessions yet.")
        sessions.forEach { s -> SessionRow(app, s) { nav.navigate("summary/${s.id}") } }
    }
}

@Composable
fun SessionRow(app: RangeApp, s: StoredSession, onClick: () -> Unit) {
    Panel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${s.drill}${if (s.manual) " (by hand)" else ""}", fontWeight = FontWeight.SemiBold)
                Muted(listOfNotNull(dateText(s.createdAt), "${s.rounds.size} round${if (s.rounds.size == 1) "" else "s"}", app.store.gun(s.gunId)?.name).joinToString(" · "))
            }
            s.averageSize()?.let { Column(horizontalAlignment = Alignment.End) { Text(mm(it.first), fontWeight = FontWeight.SemiBold); Muted(mrad(it.second)) } }
        }
    }
}

// ---------------------------------------------------------------- markup

@Composable
fun MarkupScreen(app: RangeApp, nav: NavHostController) {
    val sessions by app.store.sessions.collectAsState()
    val settings by app.store.settings.collectAsState()
    var pendingId by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    Screen("Markup", onBack = { nav.popBackStack() }) {
        Panel {
            Text("From device", fontWeight = FontWeight.SemiBold)
            Muted("Pick a photo of a card from your gallery. With nothing to compare against, every hole is a candidate: circle only the groups you want measured. It's saved as a session added by hand.")
            PhotoStep(app, "lone",
                save = { f ->
                    val id = app.store.newId(); pendingId = id
                    f.copyTo(File(app.store.sessionDir(id), "r01.jpg"), overwrite = true)
                    app.store.saveSession(StoredSession(id, System.currentTimeMillis(), "Added by hand", 1, null, settings.defaultDistanceM, manual = true))
                },
                analyse = { app.engine.lone(pendingId!!) },
                onDone = { nav.navigate("circle/$pendingId/1?from=markup") },
            )
        }
        Label("Or pick a photo by session")
        val withRounds = sessions.filter { s -> s.rounds.isNotEmpty() }
        if (withRounds.isEmpty()) Muted("No session photos yet.")
        withRounds.forEach { s ->
            SessionRow(app, s) { expanded = if (expanded == s.id) null else s.id }
            if (expanded == s.id) FlowRow(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.rounds.sortedBy { it.number }.forEach { r ->
                    AssistChip({ nav.navigate("circle/${s.id}/${r.number}?from=markup") }, { Text("Round ${r.number}${if (r.edits.isNotEmpty()) " · edited" else ""}") })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- my kit

@Composable
fun KitScreen(app: RangeApp, nav: NavHostController) {
    val kit by app.store.kit.collectAsState()
    var tab by remember { mutableStateOf("Guns") }
    var editing by remember { mutableStateOf<Any?>(null) }
    Screen("My kit", onBack = { nav.popBackStack() }, bottom = {
        MainButton("Add ${when (tab) { "Guns" -> "a gun"; "Ammo" -> "ammo"; else -> "a target" }}") {
            val id = app.store.newId()
            editing = when (tab) { "Guns" -> Gun(id, ""); "Ammo" -> Ammo(id, ""); else -> TargetItem(id, "") }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Guns", "Ammo", "Targets").forEach { t -> FilterChip(tab == t, { tab = t }, { Text(t) }) }
        }
        when (tab) {
            "Guns" -> { if (kit.guns.isEmpty()) Muted("No guns yet. The same model with a different sight is a separate entry.")
                kit.guns.forEach { g -> KitRow(g.name, listOf(g.calibre, g.sight, g.clubNumber.takeIf { it.isNotBlank() }?.let { "club no. $it" } ?: "")) { editing = g } } }
            "Ammo" -> { if (kit.ammo.isEmpty()) Muted("No ammo yet.")
                kit.ammo.forEach { a -> KitRow(a.name, listOf(a.brand, a.calibre, a.bulletWeightGr.takeIf { it.isNotBlank() }?.let { "$it gr" } ?: "", a.bulletType)) { editing = a } } }
            else -> { if (kit.targets.isEmpty()) Muted("No targets yet. This version measures only the ISSF 25 m precision card; other targets can be listed for later.")
                kit.targets.forEach { t -> KitRow(t.name, listOf(t.type)) { editing = t } } }
        }
    }
    editing?.let { item -> KitDialog(item, onClose = { editing = null }, onSave = { saved ->
        val k = app.store.kit.value
        app.store.saveKit(when (saved) {
            is Gun -> k.copy(guns = k.guns.filter { it.id != saved.id } + saved)
            is Ammo -> k.copy(ammo = k.ammo.filter { it.id != saved.id } + saved)
            is TargetItem -> k.copy(targets = k.targets.filter { it.id != saved.id } + saved)
            else -> k
        }); editing = null
    }, onDelete = { del ->
        val k = app.store.kit.value
        app.store.saveKit(when (del) {
            is Gun -> k.copy(guns = k.guns.filter { it.id != del.id })
            is Ammo -> k.copy(ammo = k.ammo.filter { it.id != del.id })
            is TargetItem -> k.copy(targets = k.targets.filter { it.id != del.id })
            else -> k
        }); editing = null
    }) }
}

@Composable
private fun KitRow(name: String, details: List<String>, onClick: () -> Unit) = Panel(Modifier.clickable(onClick = onClick)) {
    Text(name.ifBlank { "(no name)" }, fontWeight = FontWeight.SemiBold)
    details.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { Muted(it.joinToString(" · ")) }
}

@Composable
private fun KitDialog(item: Any, onClose: () -> Unit, onSave: (Any) -> Unit, onDelete: (Any) -> Unit) {
    // Field name to value, in order. Only the name is required.
    val fields = remember(item) {
        mutableStateListOf(*when (item) {
            is Gun -> arrayOf("Name" to item.name, "Calibre" to item.calibre, "Sight" to item.sight, "Club number" to item.clubNumber)
            is Ammo -> arrayOf("Product name" to item.name, "Brand" to item.brand, "Calibre" to item.calibre, "Bullet weight (grains)" to item.bulletWeightGr,
                "Bullet type" to item.bulletType, "Lot number" to item.lot)
            is TargetItem -> arrayOf("Name" to item.name, "Type" to item.type)
            else -> emptyArray<Pair<String, String>>()
        })
    }
    val choices = mapOf("Sight" to listOf("Iron Sight", "Red Dot", "Other"), "Bullet type" to listOf("Full metal jacket", "Lead round nose", "Wadcutter", "Other"))
    fun v(i: Int) = fields[i].second.trim()
    AlertDialog(onClose,
        title = { Text(when (item) { is Gun -> "Gun"; is Ammo -> "Ammo"; else -> "Target" }) },
        text = {
            Column(Modifier.verticalScrollSafe(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fields.forEachIndexed { i, (label, value) ->
                    val options = choices[label]
                    if (options != null) {
                        Label(label)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            options.forEach { o -> FilterChip(value == o, { fields[i] = label to o }, { Text(o, fontSize = 13.sp) }) }
                        }
                    } else OutlinedTextField(value, { fields[i] = label to it }, label = { Text(label) }, singleLine = true,
                        keyboardOptions = if (label.contains("weight")) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default)
                }
                Muted("Photos of kit come in a later version.")
            }
        },
        confirmButton = {
            TextButton({
                onSave(when (item) {
                    is Gun -> item.copy(name = v(0), calibre = v(1), sight = v(2), clubNumber = v(3))
                    is Ammo -> item.copy(name = v(0), brand = v(1), calibre = v(2), bulletWeightGr = v(3), bulletType = v(4), lot = v(5))
                    is TargetItem -> item.copy(name = v(0), type = v(1))
                    else -> item
                })
            }, enabled = fields.firstOrNull()?.second?.isNotBlank() == true) { Text("Save") }
        },
        dismissButton = { Row { TextButton({ onDelete(item) }) { Text("Delete") }; TextButton(onClose) { Text("Cancel") } } },
    )
}

@Composable
private fun Modifier.verticalScrollSafe(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

// ---------------------------------------------------------------- settings, about

@Composable
fun SettingsScreen(app: RangeApp, nav: NavHostController) {
    val settings by app.store.settings.collectAsState()
    var name by remember { mutableStateOf(settings.name) }
    Screen("Settings", onBack = { app.store.saveSettings(app.store.settings.value.copy(name = name.trim())); nav.popBackStack() }) {
        OutlinedTextField(name, { name = it; app.store.saveSettings(app.store.settings.value.copy(name = it.trim())) }, Modifier.fillMaxWidth(), label = { Text("Your name (for the greeting)") }, singleLine = true)
        Label("Theme")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("dark" to "Dark", "light" to "Light", "system" to "Same as phone").forEach { (k, t) ->
                FilterChip(settings.theme == k, { app.store.saveSettings(settings.copy(theme = k)) }, { Text(t) })
            }
        }
        DistancePicker(settings.defaultDistanceM) { app.store.saveSettings(app.store.settings.value.copy(defaultDistanceM = it)) }
        Muted("Distances are used to turn group sizes into angles (mrad), so 12 m and 25 m results compare.")
    }
}

@Composable
fun AboutScreen(nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    Screen("About", onBack = { nav.popBackStack() }) {
        Text("Range Target", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Muted("Test build $version · analysis core ${RangeTarget.VERSION}")
        Text("Turns photos of an ISSF 25 m precision pistol card into group sizes and offsets, by comparing each photo with the one before.")
        Text("Everything is worked out on this phone, offline. Nothing is sent anywhere unless you export it.")
        Muted("This is a first test version. Painted loops aren't read yet: you circle the groups on the phone.")
    }
}
