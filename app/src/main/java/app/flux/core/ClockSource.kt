package app.flux.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.flux.timers.ClockSample

/** Reads the three clocks the timer maths needs, in one consistent sample. */
object ClockSource {
    fun sample(context: Context): ClockSample = ClockSample(
        elapsedRealtimeMs = SystemClock.elapsedRealtime(),
        wallMs = System.currentTimeMillis(),
        bootCount = bootCount(context),
    )

    /** Settings.Global.BOOT_COUNT increments on every boot; -1 when unreadable. */
    fun bootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: SecurityException) {
        -1
    }
}
