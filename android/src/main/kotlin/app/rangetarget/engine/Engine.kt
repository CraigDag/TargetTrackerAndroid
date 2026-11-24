package app.rangetarget.engine

import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import app.rangetarget.data.StoredRound
import app.rangetarget.data.StoredSession
import app.rangetarget.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import rangetarget.core.debug.DebugSink
import rangetarget.core.flags.Flag
import rangetarget.core.flags.FlagCode
import rangetarget.core.session.AimPoint
import rangetarget.core.session.DrawnLoop
import rangetarget.core.session.LonePhoto
import rangetarget.core.session.SessionAnalyzer
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** A problem to show the shooter as it is, in plain words. */
class ShooterMessage(message: String) : Exception(message)

/** One piece of analysis running in the background, so it survives leaving the screen. */
data class Work(val key: String, val running: Boolean, val error: String? = null, val seconds: Double? = null)

/**
 * Runs the analysis core on the phone. Keeps one [SessionAnalyzer] for the
 * session being shot; if the app was closed in between, it is rebuilt by
 * replaying the saved photos.
 */
class Engine(private val store: Store, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var active: Active? = null
    private class Active(val sessionId: String, val analyzer: SessionAnalyzer, var roundsFed: Int, val views: HashMap<String, Mat>)

    private val _work = MutableStateFlow<Work?>(null)
    val work: StateFlow<Work?> = _work

    fun clearWork() { if (_work.value?.running == false) _work.value = null }

    fun start(key: String, block: suspend () -> Unit) {
        if (_work.value?.running == true) return
        _work.value = Work(key, true)
        scope.launch(Dispatchers.Default) {
            val t0 = System.nanoTime()
            try {
                block()
                _work.value = Work(key, false, seconds = (System.nanoTime() - t0) / 1e9)
            } catch (e: ShooterMessage) {
                _work.value = Work(key, false, e.message)
            } catch (e: Throwable) {
                _work.value = Work(key, false, "Something went wrong while reading the photo (${e.javaClass.simpleName}: ${e.message}). Please try again or retake it.")
            }
        }
    }

    /** Reads the blank-card photo: card position, aim points. */
    suspend fun baseline(sessionId: String) = lock.withLock {
        val s = store.session(sessionId) ?: throw ShooterMessage("Session not found")
        val dir = store.sessionDir(sessionId)
        val photo = loadPhoto(File(dir, s.baselinePhoto ?: throw ShooterMessage("No photo")))
        dropActive()
        val a = newActive(sessionId)
        val t0 = System.currentTimeMillis()
        val reading = try { a.analyzer.baseline(photo, "baseline") } finally { photo.release() }
        if (reading.card == null) {
            dropActive(a)
            throw ShooterMessage("Couldn't find the card in this photo. Make sure the whole card is in the frame, square on and without glare, then retake it.")
        }
        saveView(a, "r00", File(dir, "v00.jpg"))
        active = a
        val found = reading.aimPoints.filter { (it.centreMm.radius) > 15 }
        var next = 1
        val used = HashSet<Int>()
        val aims = listOf(StoredSession.CENTRE) + found.map { r ->
            val n = r.number?.takeIf { it > 0 && it !in used } ?: run { while (next in used || found.any { it.number == next }) next++; next }
            used += n
            AimPoint(n, r.centreMm, "found")
        }
        store.update(sessionId) { it.copy(baselineView = "v00.jpg", baselineFlags = keep(reading.flags), aims = aims.sortedBy { a2 -> a2.number }, rounds = emptyList()) }
        android.util.Log.i("RangeTarget", "baseline read in ${System.currentTimeMillis() - t0} ms: ${aims.size - 1} aim points")
        System.currentTimeMillis() - t0
    }

    /** Reads round [n]'s photo (already saved in the session folder) against the previous one. */
    suspend fun round(sessionId: String, n: Int, photoName: String, distanceM: Double, gunId: String?, ammoId: String?) = lock.withLock {
        val s = store.session(sessionId) ?: throw ShooterMessage("Session not found")
        val dir = store.sessionDir(sessionId)
        val a = ready(s, n - 1)
        val photo = loadPhoto(File(dir, photoName))
        val t0 = System.currentTimeMillis()
        val r = try { a.analyzer.round(photo, photoName, n) } finally { photo.release() }
        val ms = System.currentTimeMillis() - t0
        if (r.alignment == null) {
            dropActive(a)
            throw ShooterMessage("Couldn't find the card in this photo, or couldn't line it up with the last one. Retake it with the whole card in the frame.")
        }
        a.roundsFed = n
        val viewName = "v%02d.jpg".format(n)
        saveView(a, "r%02d".format(n), File(dir, viewName))
        // Paint loops are not read in v1 (docs/SPEC.md, App v1), so the shots start ungrouped.
        val shots = r.shots.map { it.copy(groupId = null, fromAimMm = null, flags = it.flags.filter { f -> f in KEEP_SHOT_FLAGS }) }
        val paint = r.groups.mapNotNull { g -> r.ink.firstOrNull { it.id == g.loopId }?.let { DrawnLoop(it.outlineMm, g.aimPointNumber, it.colourName, it.colourRgb) } }
        val many = rangetarget.core.session.PhotoCheck.tooManyHoles(shots.size, s.shotsPerGroup?.let { it * s.groupsPerRound })
        val round = StoredRound(n, photoName, viewName, distanceM, gunId, ammoId, shots, flags = keep(r.flags) + listOfNotNull(many), paintLoops = paint, analysisMillis = ms)
        android.util.Log.i("RangeTarget", "round $n read in $ms ms: ${shots.size} new holes")
        store.update(sessionId) { it.copy(rounds = it.rounds.filter { x -> x.number < n } + round) }
    }

    /** A lone photo from the gallery (Markup, "from device"): every hole is a candidate. */
    suspend fun lone(sessionId: String) = lock.withLock {
        val s = store.session(sessionId) ?: throw ShooterMessage("Session not found")
        val dir = store.sessionDir(sessionId)
        val photo = loadPhoto(File(dir, "r01.jpg"))
        val t0 = System.currentTimeMillis()
        val r = try { LonePhoto.read(photo) } finally { photo.release() }
            ?: throw ShooterMessage("Couldn't find an ISSF precision card in this photo. The whole card needs to be in the picture.")
        val ms = System.currentTimeMillis() - t0
        writeJpeg(r.view, File(dir, "v01.jpg")); r.view.release()
        val round = StoredRound(1, "r01.jpg", "v01.jpg", s.distanceM, s.gunId, s.ammoId, r.shots, flags = keep(r.flags), analysisMillis = ms)
        store.update(sessionId) { it.copy(rounds = listOf(round)) }
    }

    /** An analyzer that has seen the baseline and rounds 1..[rounds], replaying saved photos if needed. */
    private fun ready(s: StoredSession, rounds: Int): Active {
        active?.let { if (it.sessionId == s.id && it.roundsFed == rounds) return it }
        dropActive()
        val dir = store.sessionDir(s.id)
        val a = newActive(s.id)
        val base = loadPhoto(File(dir, s.baselinePhoto ?: throw ShooterMessage("This session has no blank-card photo")))
        try { a.analyzer.baseline(base, "baseline") } finally { base.release() }
        for (k in 1..rounds) {
            val r = s.round(k) ?: throw ShooterMessage("Round $k's photo is missing, so the next round can't be compared with it")
            val p = loadPhoto(File(dir, r.photo))
            try { a.analyzer.round(p, r.photo, k) } finally { p.release() }
        }
        a.views.values.forEach { it.release() }; a.views.clear()
        a.roundsFed = rounds
        active = a
        return a
    }

    private fun newActive(id: String): Active {
        val views = HashMap<String, Mat>()
        val sink = DebugSink { name, m -> views.remove(name)?.release(); views[name] = m }
        return Active(id, SessionAnalyzer(null, DebugSink.NONE, sink), 0, views)
    }

    private fun dropActive(a: Active? = active) {
        a ?: return
        a.analyzer.release(); a.views.values.forEach { it.release() }; a.views.clear()
        if (active === a) active = null
    }

    private fun saveView(a: Active, name: String, f: File) {
        val m = a.views.remove(name) ?: throw ShooterMessage("The flattened card picture is missing")
        writeJpeg(m, f); m.release()
    }

    companion object {
        /** Photos are scaled so the long side is at most this (about 5 px per mm on a card that fills the frame). */
        const val MAX_SIDE_PX = 3200

        private val KEEP_FLAGS = setOf(
            FlagCode.BLACK_DISC_DOUBTFUL, FlagCode.RING_FIT_POOR, FlagCode.PAPER_EDGE_MISSING,
            FlagCode.UNRESOLVABLE_CLUSTER, FlagCode.ALIGNMENT_POOR, FlagCode.CARD_NOT_FOUND,
            FlagCode.PHOTO_BLURRY, FlagCode.PHOTO_TOO_FAR, FlagCode.PHOTO_TILTED, FlagCode.PHOTO_UNEVEN_LIGHT, FlagCode.PHOTO_GLARE, FlagCode.PHOTO_TOO_MANY_HOLES,
        )
        private val KEEP_SHOT_FLAGS = setOf(FlagCode.OVERLAPPING_HOLES, FlagCode.HOLE_ON_PAPER_EDGE, FlagCode.HOLE_UNCERTAIN, FlagCode.HOLE_GREW)

        fun keep(flags: List<Flag>) = flags.filter { it.code in KEEP_FLAGS }

        /** Reads a JPEG upright (EXIF orientation applied) and no bigger than [MAX_SIDE_PX]. */
        fun loadPhoto(f: File): Mat {
            if (!f.exists()) throw ShooterMessage("The photo file is missing")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            val big = max(bounds.outWidth, bounds.outHeight) > MAX_SIDE_PX * 2
            val flags = Imgcodecs.IMREAD_IGNORE_ORIENTATION or (if (big) Imgcodecs.IMREAD_REDUCED_COLOR_2 else Imgcodecs.IMREAD_COLOR)
            var m = Imgcodecs.imread(f.path, flags)
            if (m.empty()) throw ShooterMessage("Couldn't open this photo. Is it a JPEG?")
            val turn = when (runCatching { ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> Core.ROTATE_90_CLOCKWISE
                ExifInterface.ORIENTATION_ROTATE_180 -> Core.ROTATE_180
                ExifInterface.ORIENTATION_ROTATE_270 -> Core.ROTATE_90_COUNTERCLOCKWISE
                else -> null
            }
            if (turn != null) { val r = Mat(); Core.rotate(m, r, turn); m.release(); m = r }
            val side = max(m.cols(), m.rows())
            if (side > MAX_SIDE_PX) {
                val k = MAX_SIDE_PX.toDouble() / side
                val r = Mat(); Imgproc.resize(m, r, Size((m.cols() * k).roundToInt().toDouble(), (m.rows() * k).roundToInt().toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                m.release(); m = r
            }
            return m
        }

        fun writeJpeg(m: Mat, f: File) {
            Imgcodecs.imwrite(f.path, m, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90))
        }
    }
}
