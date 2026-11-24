package app.rangetarget

import android.util.Log
import app.rangetarget.data.Demo
import app.rangetarget.data.GroupCall
import rangetarget.core.geometry.PointMm
import java.io.File

/**
 * Builds the sample session end to end without taps (painted loops filled in
 * as the groups), for the automatic check on an emulator. Logs how long each
 * step took on the device.
 */
object AutoDemo {
    const val ID = "sample-check"

    fun run(app: RangeApp) {
        app.engine.start("auto-demo") {
            val t0 = System.currentTimeMillis()
            app.store.deleteSession(ID)
            Demo.create(app.store, ID)
            val dir = app.store.sessionDir(ID)
            Demo.copyAsset(app, Demo.assetFor(0), File(dir, "baseline.jpg"))
            app.store.update(ID) { it.copy(baselinePhoto = "baseline.jpg") }
            app.engine.baseline(ID)
            for (n in 1..Demo.ROUNDS) {
                // From round 2 on, carry on from saved state, as after the app was closed.
                if (n > 1) app.engine.forget()
                val name = "r%02d.jpg".format(n)
                Demo.copyAsset(app, Demo.assetFor(n), File(dir, name))
                app.engine.round(ID, n, name, if (n == 2) 25.0 else 12.0, null, null)
                app.store.update(ID) { s -> s.copy(rounds = s.rounds.map { r -> if (r.number != n) r else r.copy(marking = r.marking.copy(loops = r.paintLoops), original = r.marking.copy(loops = r.paintLoops)) }) }
            }
            // Example calls for round 1 (a little off each group's middle), so the screenshots show them.
            app.store.session(ID)?.let { s0 ->
                val offs = listOf(PointMm(6.0, -8.0), PointMm(-12.0, 3.0), PointMm(2.0, 2.0), PointMm(-5.0, 14.0))
                val calls = s0.round(1)?.let { r -> s0.groups(r).groups.mapNotNull { it.metrics?.centreMm }.mapIndexed { i, c -> GroupCall(1, c + offs[i % offs.size]) } }.orEmpty()
                app.store.update(ID) { it.copy(calls = calls, rounds = it.rounds.map { r -> if (r.number == 1) r.copy(note = "Example note: felt the trigger pull wander on group 2.") else r }) }
            }
            app.store.update(ID) { it.copy(finished = true) }
            val s = app.store.session(ID)!!
            Log.i("RangeTarget", "demo ready in ${System.currentTimeMillis() - t0} ms: " +
                s.rounds.joinToString { r -> "round ${r.number}: ${r.shots.size} holes, ${s.groups(r).groups.size} groups, ${r.analysisMillis} ms" })
        }
    }
}
