package app.rangetarget.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The frame every screen uses: title bar with back, scrolling body, optional buttons pinned at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable RowScope.() -> Unit)? = null,
    scroll: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            if (bottom != null) Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), content = bottom,
            )
        },
    ) { pad ->
        val m = Modifier.fillMaxSize().padding(pad)
        Column(
            (if (scroll) m.verticalScroll(rememberScrollState()) else m).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), content = content,
        )
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, warn: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (warn) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
}

@Composable
fun Label(text: String) = Text(text.uppercase(), fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Muted(text: String) = Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun Big(text: String) = Text(text, fontSize = 24.sp, fontWeight = FontWeight.Bold)

@Composable
fun RowScope.MainButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, Modifier.weight(1f), enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(vertical = 14.dp)) { Text(text, fontSize = 16.sp) }

@Composable
fun RowScope.SecondButton(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick, Modifier.weight(1f), enabled = enabled, shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(vertical = 14.dp)) { Text(text, fontSize = 16.sp) }

/** Take a photo with the phone's camera app, or pick one from the gallery. The result is a JPEG file in the cache. */
class PhotoSource(val takePhoto: () -> Unit, val pickPhoto: () -> Unit)

@Composable
fun rememberPhotoSource(onPhoto: (File) -> Unit, onError: (String) -> Unit): PhotoSource {
    val ctx = LocalContext.current
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending?.let { File(it) }
        if (ok && f != null && f.length() > 0) onPhoto(f)
        pending = null
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) runCatching { copyToCache(ctx, uri) }.onSuccess(onPhoto).onFailure { onError("Couldn't open that photo") }
    }
    return PhotoSource(
        takePhoto = {
            val f = newCacheFile(ctx, "capture", "jpg")
            pending = f.path
            try { camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)) }
            catch (e: ActivityNotFoundException) { onError("No camera app found on this phone") }
        },
        pickPhoto = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}

fun newCacheFile(ctx: Context, dir: String, ext: String): File =
    File(ctx.cacheDir, dir).apply { mkdirs() }.let { File(it, "${System.currentTimeMillis()}.$ext") }

private fun copyToCache(ctx: Context, uri: Uri): File {
    val f = newCacheFile(ctx, "picked", "jpg")
    ctx.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
    return f
}

fun dateText(millis: Long): String = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(millis))
fun timeText(millis: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
fun mm(v: Double) = "%.0f mm".format(v)
fun mrad(v: Double) = "%.1f mrad".format(v)
fun metres(v: Double) = if (v % 1.0 == 0.0) "%.0f m".format(v) else "%.1f m".format(v)
fun aimName(n: Int?) = when (n) { null -> "no aim point"; 0 -> "card centre"; else -> "aim point $n" }
