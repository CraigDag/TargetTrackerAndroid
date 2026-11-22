package app.rangetarget

import android.app.Application
import app.rangetarget.data.Store
import app.rangetarget.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

class RangeApp : Application() {
    lateinit var store: Store
        private set
    lateinit var engine: Engine
        private set
    val scope = CoroutineScope(SupervisorJob())
    /** A screen to open, asked for from outside (used by the automatic emulator check). */
    val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        // The OpenCV native library from the official Android SDK (same version as desktop).
        System.loadLibrary("opencv_java4")
        store = Store(filesDir)
        engine = Engine(store, scope)
    }
}
