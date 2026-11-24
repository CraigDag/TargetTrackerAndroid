package app.rangetarget.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

/**
 * The in-app camera (docs/SPEC.md, App v1): a framing guide for the card, a
 * level that shows when the phone is square on, and tap to focus. The phone's
 * own camera app stays available as a fallback.
 */
@Composable
fun CameraDialog(onPhoto: (File) -> Unit, onClose: () -> Unit, onUseCameraApp: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (!ok) onUseCameraApp() }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }

    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (granted) CameraBody(onPhoto, onClose, onUseCameraApp)
            else Text("Waiting for permission to use the camera…", Modifier.align(Alignment.Center).padding(24.dp), color = Color.White)
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
@Composable
private fun BoxScope.CameraBody(onPhoto: (File) -> Unit, onClose: () -> Unit, onUseCameraApp: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var focusAt by remember { mutableStateOf<Offset?>(null) }
    val level = rememberLevel()

    AndroidView(
        factory = { c ->
            PreviewView(c).also { pv ->
                pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                pv.scaleType = PreviewView.ScaleType.FIT_CENTER
                val future = ProcessCameraProvider.getInstance(c)
                future.addListener({
                    runCatching {
                        val provider = future.get()
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                    }.onFailure { error = "The camera couldn't start here. Use the phone's camera app instead." }
                }, ContextCompat.getMainExecutor(c))
                pv.setOnTouchListener { _, e ->
                    if (e.action == MotionEvent.ACTION_UP) {
                        val point = pv.meteringPointFactory.createPoint(e.x, e.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        focusAt = Offset(e.x, e.y)
                    }
                    true
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    // Some phones (and emulators) have no usable back camera: say so rather than show black.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5000)
        if (camera == null && error == null) error = "The camera hasn't started. Use the phone's camera app instead."
    }
    DisposableEffect(Unit) { onDispose { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } } }

    // Framing guide: the card should fill most of this square.
    val square = level.square
    Canvas(Modifier.fillMaxSize()) {
        val side = size.minDimension * 0.86f
        val tl = Offset((size.width - side) / 2, (size.height - side) / 2)
        val col = if (square) Color(0xFF66BB6A) else Color.White
        val arm = side * 0.12f; val w = 4.dp.toPx()
        for ((cx, cy) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            val x = tl.x + cx * side; val y = tl.y + cy * side
            val dx = if (cx == 0f) arm else -arm; val dy = if (cy == 0f) arm else -arm
            drawLine(col, Offset(x, y), Offset(x + dx, y), w); drawLine(col, Offset(x, y), Offset(x, y + dy), w)
        }
        drawRect(col.copy(alpha = 0.35f), tl, Size(side, side), style = Stroke(1.dp.toPx()))
        focusAt?.let { drawCircle(Color.Yellow, 28.dp.toPx(), it, style = Stroke(2.dp.toPx())) }
    }

    Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Fit the whole card inside the corners", color = Color.White, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        Spacer(Modifier.height(6.dp))
        Text(level.words, color = if (square) Color(0xFF66BB6A) else Color(0xFFFFCA28), fontSize = 15.sp,
            modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
        error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = Color(0xFFFF8A80), modifier = Modifier.background(Color(0xCC000000)).padding(8.dp)) }
    }

    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClose) { Text("Cancel", color = Color.White) }
        Box(Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(6.dp)
            .background(if (busy) Color.Gray else Color.White, CircleShape)
            .clickable(enabled = !busy && camera != null) {
                busy = true
                val f = newCacheFile(ctx, "capture", "jpg")
                capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { busy = false; onPhoto(f) }
                        override fun onError(e: ImageCaptureException) { busy = false; error = "Couldn't take the photo (${e.message}). Try again." }
                    })
            })
        TextButton(onUseCameraApp) { Text("Camera app", color = Color.White) }
    }
    Text("Tap the card to focus", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp), color = Color(0xCCFFFFFF), fontSize = 13.sp)
}

/** How square on the phone is: to a card on an upright frame, or to one lying flat. */
class Level(val square: Boolean, val words: String)

@Composable
private fun rememberLevel(): Level {
    val ctx = LocalContext.current
    var g by remember { mutableStateOf(floatArrayOf(0f, 9.8f, 0f)) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { g = floatArrayOf(0.8f * g[0] + 0.2f * e.values[0], 0.8f * g[1] + 0.2f * e.values[1], 0.8f * g[2] + 0.2f * e.values[2]) }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sensor?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(l) }
    }
    return levelOf(g[0].toDouble(), g[1].toDouble(), g[2].toDouble())
}

/** Gravity in phone axes (x right, y up the screen, z out of the screen) to advice. */
fun levelOf(gx: Double, gy: Double, gz: Double): Level {
    val upright = abs(gy) + abs(gx) >= abs(gz)
    return if (upright) {
        // Card on an upright frame: the phone should stand straight up, not lean forward or back.
        val up = max(abs(gx), abs(gy))
        val lean = Math.toDegrees(atan2(gz, up))
        when {
            abs(lean) <= SQUARE_DEG -> Level(true, "Square on ✓")
            lean > 0 -> Level(false, "Pointing down: tilt the top of the phone back (%.0f°)".format(abs(lean)))
            else -> Level(false, "Pointing up: tilt the top of the phone forward (%.0f°)".format(abs(lean)))
        }
    } else {
        // Card lying flat: the phone should be flat above it.
        val tilt = Math.toDegrees(atan2(kotlin.math.hypot(gx, gy), abs(gz)))
        if (tilt <= SQUARE_DEG) Level(true, "Flat above the card ✓") else Level(false, "Hold the phone flat above the card (%.0f° off)".format(tilt))
    }
}

private const val SQUARE_DEG = 5.0
