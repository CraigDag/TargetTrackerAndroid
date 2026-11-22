package app.rangetarget

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import app.rangetarget.ui.AppNav
import app.rangetarget.ui.RangeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RangeApp
        handle(intent)
        setContent {
            val settings by app.store.settings.collectAsState()
            val dark = when (settings.theme) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            RangeTheme(dark) { AppNav(app) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** Extras used by the automatic emulator check: "demo" = "auto" builds the sample session; "route" opens a screen. */
    private fun handle(intent: Intent?) {
        val app = application as RangeApp
        intent?.getStringExtra("name")?.let { n -> if (app.store.settings.value.name.isBlank()) app.store.saveSettings(app.store.settings.value.copy(name = n)) }
        if (intent?.getStringExtra("demo") == "auto") AutoDemo.run(app)
        intent?.getStringExtra("route")?.let { app.pendingRoute.value = it }
    }
}
