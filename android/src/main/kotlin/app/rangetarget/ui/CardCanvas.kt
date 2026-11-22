package app.rangetarget.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rangetarget.core.geometry.PointMm
import rangetarget.core.session.AimPoint
import rangetarget.core.session.ViewBox
import java.io.File
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A shot to draw: where, and in which colour (null: not in any group yet). */
class ShotMark(val centreMm: PointMm, val colour: Color?, val doubtful: Boolean = false)

/** A loop to draw. */
class LoopMark(val outlineMm: List<PointMm>, val colour: Color, val label: String? = null)

fun hexColour(rgb: String): Color = runCatching { Color(android.graphics.Color.parseColor(rgb)) }.getOrDefault(Color.White)

/**
 * The flattened card picture with holes, loops and aim points drawn on it.
 * One finger draws a loop ([onLoop]) or taps ([onTap]); two fingers zoom and pan.
 */
@Composable
fun CardCanvas(
    viewFile: File?,
    aims: List<AimPoint>,
    shots: List<ShotMark>,
    loops: List<LoopMark>,
    modifier: Modifier = Modifier,
    onTap: ((PointMm) -> Unit)? = null,
    onLoop: ((List<PointMm>) -> Unit)? = null,
) {
    val box = ViewBox.DEFAULT
    var image by remember(viewFile) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(viewFile) {
        image = withContext(Dispatchers.IO) {
            viewFile?.takeIf { it.exists() }?.let { f ->
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap()
            }
        }
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val text = rememberTextMeasurer()
    val tap by rememberUpdatedState(onTap)
    val loop by rememberUpdatedState(onLoop)
    val ring = MaterialTheme.colorScheme.primary

    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(box.widthPx.toFloat() / box.heightPx)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1F22))
                .pointerInput(Unit) {
                    val k = size.width.toFloat() / box.widthPx
                    fun toMm(p: Offset): PointMm {
                        val c = (p - offset) / zoom
                        return box.toMm(c.x / k.toDouble(), c.y / k.toDouble())
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val path = arrayListOf(down.position)
                        var multi = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                multi = true; live = emptyList()
                                val z = ev.calculateZoom(); val pan = ev.calculatePan()
                                val newScale = (zoom * z).coerceIn(1f, 8f)
                                val centroid = pressed.map { it.position }.reduce { a, b -> a + b } / pressed.size.toFloat()
                                // Zoom about the fingers, then pan, then keep the picture on screen.
                                var o = centroid - (centroid - offset) * (newScale / zoom) + pan
                                o = Offset(o.x.coerceIn(size.width * (1 - newScale), 0f), o.y.coerceIn(size.height * (1 - newScale), 0f))
                                zoom = newScale; offset = o
                                ev.changes.forEach { it.consume() }
                            } else if (!multi && loop != null) {
                                path += pressed[0].position
                                live = path.toList()
                                pressed[0].consume()
                            }
                        }
                        live = emptyList()
                        if (!multi) {
                            val length = path.zipWithNext().sumOf { (a, b) -> hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) }
                            val drawLoop = loop
                            if (length < 30.0 || drawLoop == null) tap?.invoke(toMm(path.first()))
                            else drawLoop(path.map { toMm(it) })
                        }
                    }
                },
        ) {
            val k = size.width / box.widthPx
            fun px(p: PointMm): Offset = Offset(((p.x - box.left) * box.pxPerMm).toFloat() * k, ((box.top - p.y) * box.pxPerMm).toFloat() * k)
            val mmPx = (box.pxPerMm * k).toFloat()
            withTransform({ translate(offset.x, offset.y); scale(zoom, zoom, Offset.Zero) }) {
                image?.let { drawImage(it, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())) }
                val line = 1.6.dp.toPx() / zoom
                for (l in loops) {
                    if (l.outlineMm.size < 3) continue
                    val p = Path().apply { l.outlineMm.forEachIndexed { i, q -> val o = px(q); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }; close() }
                    drawPath(p, Color.Black.copy(alpha = 0.6f), style = Stroke(line * 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(p, l.colour, style = Stroke(line * 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    l.label?.let { label ->
                        val top = l.outlineMm.maxBy { it.y }
                        drawText(text, label, px(top) + Offset(0f, -18.sp.toPx() / zoom),
                            style = TextStyle(color = l.colour, fontSize = (13 / zoom).sp, fontWeight = FontWeight.Bold, background = Color.Black.copy(alpha = 0.55f)))
                    }
                }
                for (s in shots) {
                    val c = px(s.centreMm)
                    val colour = s.colour ?: Color(0xFFFF5252)
                    drawCircle(Color.Black.copy(alpha = 0.7f), radius = 5.5f * mmPx, center = c, style = Stroke(line * 2.4f))
                    drawCircle(colour, radius = 5.5f * mmPx, center = c, style = Stroke(line * 1.3f))
                    if (s.doubtful) drawCircle(Color(0xFFF0A840), radius = 8f * mmPx, center = c, style = Stroke(line))
                }
                for (a in aims) {
                    val c = px(a.centreMm)
                    val r = (if (a.number == 0) 4f else 9f) * mmPx
                    drawCircle(Color.Black.copy(alpha = 0.6f), radius = r, center = c, style = Stroke(line * 2.4f))
                    drawCircle(ring, radius = r, center = c, style = Stroke(line * 1.2f))
                    drawLine(ring, c - Offset(r * 1.6f, 0f), c + Offset(r * 1.6f, 0f), line)
                    drawLine(ring, c - Offset(0f, r * 1.6f), c + Offset(0f, r * 1.6f), line)
                    if (a.number > 0) drawText(text, a.number.toString(), c + Offset(r * 1.2f, -r * 2.6f),
                        style = TextStyle(color = ring, fontSize = (14 / zoom).sp, fontWeight = FontWeight.Bold, background = Color.Black.copy(alpha = 0.55f)))
                }
            }
            if (live.size > 1) {
                val p = Path().apply { live.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(p, Color.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (image == null) Text("Loading picture…", Modifier.align(Alignment.Center), color = Color.White)
        if (zoom > 1.01f) TextButton({ zoom = 1f; offset = Offset.Zero }, Modifier.align(Alignment.TopEnd).padding(4.dp)) { Text("Zoom out") }
    }
}
