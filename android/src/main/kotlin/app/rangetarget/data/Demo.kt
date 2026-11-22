package app.rangetarget.data

import android.content.Context
import java.io.File

/**
 * The sample session: synthetic photos of a card with printed aim points and
 * painted loops (from test_data), so the app can be tried before a range trip.
 */
object Demo {
    const val ASSET_DIR = "demo"
    fun assetFor(round: Int) = if (round == 0) "$ASSET_DIR/baseline.jpg" else "$ASSET_DIR/round_%02d.jpg".format(round)
    const val ROUNDS = 2

    fun create(store: Store, id: String = store.newId()): StoredSession {
        val s = StoredSession(id, System.currentTimeMillis(), "Sample session", 4, 5, 12.0, demo = true)
        store.saveSession(s)
        return s
    }

    fun copyAsset(ctx: Context, name: String, to: File): File {
        ctx.assets.open(name).use { input -> to.outputStream().use { input.copyTo(it) } }
        return to
    }
}
