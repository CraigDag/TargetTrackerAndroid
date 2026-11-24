@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package app.rangetarget.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.rangetarget.RangeApp
import app.rangetarget.data.Demo
import app.rangetarget.data.StoredSession
import kotlinx.coroutines.launch

/** Where to pick up an unfinished session. */
fun resumeRoute(s: StoredSession): String {
    if (s.manual) return "summary/${s.id}"
    if (s.baselineView == null) return "baseline/${s.id}"
    val last = s.rounds.maxByOrNull { it.number } ?: return "cardcheck/${s.id}"
    if (last.original == null) return "circle/${s.id}/${last.number}"
    return "round/${s.id}/${last.number + 1}"
}

@Composable
fun AppNav(app: RangeApp) {
    val nav = rememberNavController()
    val pending by app.pendingRoute.collectAsState()
    LaunchedEffect(pending) { pending?.let { r -> app.pendingRoute.value = null; runCatching { nav.navigate(r) } } }
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(app, nav) }
        composable("plan") { PlanScreen(app, nav) }
        composable("baseline/{sid}") { BaselineScreen(app, nav, it.arguments!!.getString("sid")!!) }
        composable("cardcheck/{sid}") { CardCheckScreen(app, nav, it.arguments!!.getString("sid")!!) }
        composable("round/{sid}/{n}") { RoundPhotoScreen(app, nav, it.arguments!!.getString("sid")!!, it.arguments!!.getString("n")!!.toInt()) }
        composable("circle/{sid}/{n}?from={from}", listOf(navArgument("from") { defaultValue = "session" })) {
            CircleScreen(app, nav, it.arguments!!.getString("sid")!!, it.arguments!!.getString("n")!!.toInt(), it.arguments!!.getString("from") ?: "session")
        }
        composable("results/{sid}/{n}?from={from}", listOf(navArgument("from") { defaultValue = "session" })) {
            ResultsScreen(app, nav, it.arguments!!.getString("sid")!!, it.arguments!!.getString("n")!!.toInt(), it.arguments!!.getString("from") ?: "session")
        }
        composable("summary/{sid}") { SummaryScreen(app, nav, it.arguments!!.getString("sid")!!) }
        composable("stats") { StatsScreen(app, nav) }
        composable("patterns?sid={sid}", listOf(navArgument("sid") { nullable = true; defaultValue = null })) {
            PatternsScreen(app, nav, it.arguments?.getString("sid"))
        }
        composable("markup") { MarkupScreen(app, nav) }
        composable("kit") { KitScreen(app, nav) }
        composable("settings") { SettingsScreen(app, nav) }
        composable("about") { AboutScreen(nav) }
        composable("guide") { GuideScreen(app, nav) }
    }
}

fun NavHostController.home() = navigate("home") { popUpTo("home") { inclusive = true } }

@Composable
fun HomeScreen(app: RangeApp, nav: NavHostController) {
    val settings by app.store.settings.collectAsState()
    val sessions by app.store.sessions.collectAsState()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(settings.name.isBlank()) }

    if (askName) NameDialog(settings.name, onDone = { app.store.saveSettings(settings.copy(name = it)); askName = false })

    Screen("Range Target", onBack = null, actions = {
        Box {
            IconButton({ menu = true }) { Icon(Icons.Filled.Menu, "Menu") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("My kit") }, { menu = false; nav.navigate("kit") })
                DropdownMenuItem({ Text("Settings") }, { menu = false; nav.navigate("settings") })
                DropdownMenuItem({ Text("Export everything") }, {
                    menu = false; exporting = true
                    app.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val f = runCatching { Export.everything(ctx, app.store) }.getOrNull()
                        exporting = false
                        if (f != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { Export.share(ctx, listOf(f), "application/zip") }
                    }
                })
                DropdownMenuItem({ Text("How it works") }, { menu = false; nav.navigate("guide") })
                DropdownMenuItem({ Text("About") }, { menu = false; nav.navigate("about") })
            }
        }
    }) {
        Text(if (settings.name.isBlank()) "Welcome" else "Welcome back, ${settings.name}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (exporting) Muted("Packing up everything to export…")

        // The big stats tile.
        val sized = sessions.mapNotNull { s -> s.averageSize()?.let { s to it } }
        Card(
            Modifier.fillMaxWidth().clickable { nav.navigate("stats") }, shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.padding(18.dp).heightIn(min = 150.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Label("View stats")
                if (sized.isEmpty()) {
                    Big("No groups yet")
                    Muted("Shoot a session and circle your groups to see your average group size here.")
                } else {
                    val (s, size) = sized.first()
                    Big("${mm(size.first)} average group")
                    Muted("Last session ${dateText(s.createdAt)} at ${metres(s.distanceM)} · ${sessions.size} session${if (sessions.size == 1) "" else "s"} in total")
                    if (sized.size >= 2) SizeChart(sized.reversed().map { it.second.second }, Modifier.fillMaxWidth().height(70.dp))
                }
            }
        }
        val open = sessions.firstOrNull { !it.finished && !it.manual }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MainButton("New session") { nav.navigate("plan") }
            SecondButton("Markup") { nav.navigate("markup") }
        }
        if (open != null) Panel(Modifier.clickable { nav.navigate(resumeRoute(open)) }) {
            Label("Unfinished session")
            Text("${open.drill}, ${dateText(open.createdAt)}", fontWeight = FontWeight.SemiBold)
            Muted("${open.rounds.size} round${if (open.rounds.size == 1) "" else "s"} so far. Tap to carry on.")
        }
        if (sessions.none { it.demo }) TextButton({ val d = Demo.create(app.store); nav.navigate("baseline/${d.id}") }) { Text("Try a sample session (no range needed)") }
        if (sessions.size < 3) TextButton({ nav.navigate("guide") }) { Text("New here? How it works") }
        Muted("Everything stays on this phone unless you export it.")
    }
}

@Composable
fun NameDialog(current: String, onDone: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("What should the app call you?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Muted("Just for the greeting. There are no accounts and nothing is sent anywhere.")
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Your name") })
            }
        },
        confirmButton = { TextButton({ onDone(name.trim()) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton({ onDone("Shooter") }) { Text("Skip") } },
    )
}
