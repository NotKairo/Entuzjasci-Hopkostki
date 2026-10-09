package app.galaxypulse.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Event-driven battery watcher: one registered receiver for `ACTION_BATTERY_CHANGED` (a sticky system
 * broadcast Android already sends on change). No polling, no timers, no wake-ups of its own.
 * Only started while the overlay service runs.
 */
class BatteryRepository(
    context: Context,
    private val lowThresholdPercent: () -> Int,
) {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private var detector = BatteryEventDetector(lowThresholdPercent)

    private val _reading = MutableStateFlow<BatteryReading?>(null)
    val reading: StateFlow<BatteryReading?> = _reading

    private val _events = MutableSharedFlow<BatteryEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<BatteryEvent> = _events

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent)
    }

    fun start() {
        if (registered) return
        detector = BatteryEventDetector(lowThresholdPercent)
        // Registering for a sticky broadcast returns the current value immediately.
        val sticky = ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        sticky?.let { handle(it) }
    }

    fun stop() {
        if (!registered) return
        appContext.unregisterReceiver(receiver)
        registered = false
    }

    /** Android's own estimate of time until full, or null when it has none. Never computed by us. */
    fun timeToFullMs(): Long? =
        batteryManager?.computeChargeTimeRemaining()?.takeIf { it > 0L }

    private fun handle(intent: Intent) {
        val reading = parse(intent) ?: return
        if (reading != _reading.value) _reading.value = reading
        detector.onReading(reading).forEach { _events.tryEmit(it) }
    }

    private fun parse(intent: Intent): BatteryReading? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = (level * 100f / scale).toInt().coerceIn(0, 100)

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plug = when {
            plugged == 0 -> null
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> PlugType.Wireless
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> PlugType.Ac
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> PlugType.Usb
            plugged and BatteryManager.BATTERY_PLUGGED_DOCK != 0 -> PlugType.Dock
            else -> PlugType.Unknown
        }
        // EXTRA_TEMPERATURE is tenths of a degree Celsius; ignore it unless the battery is present and the value is plausible.
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        val temperature = if (present && tempTenths in -200..800) tempTenths / 10f else null

        return BatteryReading(
            levelPercent = percent,
            pluggedIn = plugged != 0,
            full = status == BatteryManager.BATTERY_STATUS_FULL,
            plug = plug,
            temperatureC = temperature,
        )
    }
}
