package app.rangetarget.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Colours from the agreed v1 mock-ups.
private val Dark = darkColorScheme(
    primary = Color(0xFF4FB3D3), onPrimary = Color(0xFF06222C),
    primaryContainer = Color(0xFF1D84A6), onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFF173440), onSecondaryContainer = Color(0xFFE4EAED),
    background = Color(0xFF101417), onBackground = Color(0xFFE6ECEF),
    surface = Color(0xFF101417), onSurface = Color(0xFFE6ECEF),
    surfaceVariant = Color(0xFF1B2226), onSurfaceVariant = Color(0xFF93A1A9),
    surfaceContainer = Color(0xFF1B2226), surfaceContainerHigh = Color(0xFF222B30), surfaceContainerLow = Color(0xFF161C1F),
    outline = Color(0xFF2A3338), outlineVariant = Color(0xFF2A3338),
    tertiary = Color(0xFFF0A840), tertiaryContainer = Color(0xFF3A2A10), onTertiaryContainer = Color(0xFFE6ECEF),
    error = Color(0xFFFF8A80),
)
private val Light = lightColorScheme(
    primary = Color(0xFF0F6E8C), onPrimary = Color.White,
    primaryContainer = Color(0xFF1D84A6), onPrimaryContainer = Color.White,
    secondaryContainer = Color(0xFFDCEDF2), onSecondaryContainer = Color(0xFF1B2328),
    background = Color(0xFFEEF1F3), onBackground = Color(0xFF1B2328),
    surface = Color(0xFFEEF1F3), onSurface = Color(0xFF1B2328),
    surfaceVariant = Color.White, onSurfaceVariant = Color(0xFF5D6A72),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF6F8F9), surfaceContainerLow = Color.White,
    outline = Color(0xFFD5DBDF), outlineVariant = Color(0xFFD5DBDF),
    tertiary = Color(0xFFB26A00), tertiaryContainer = Color(0xFFFFF1DB), onTertiaryContainer = Color(0xFF1B2328),
)

/** Good-news green for "on target" text. */
val Good = Color(0xFF5CC489)

@Composable
fun RangeTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
