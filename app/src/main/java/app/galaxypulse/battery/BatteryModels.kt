package app.galaxypulse.battery

import app.galaxypulse.engine.ActivityPayload

enum class PlugType(val label: String) {
    Ac("Fast charger"), Usb("USB"), Wireless("Wireless"), Dock("Dock"), Unknown("Charger"),
}

/** Raw reading from the sticky battery broadcast. */
data class BatteryReading(
    val levelPercent: Int,
    /** True while external power is connected (stays true once the battery is full). */
    val pluggedIn: Boolean,
    /** Android reports BATTERY_STATUS_FULL. */
    val full: Boolean,
    val plug: PlugType?,
    /** Degrees Celsius, null when the platform reports nothing plausible. */
    val temperatureC: Float?,
)

enum class ChargingEventType { Started, Disconnected, Full }

data class ChargingPayload(
    val type: ChargingEventType,
    val levelPercent: Int,
    val plug: PlugType?,
    /** Only set when Android itself estimates it (BatteryManager.computeChargeTimeRemaining). */
    val timeToFullMs: Long?,
    val temperatureC: Float?,
) : ActivityPayload

data class BatteryAlertPayload(
    val levelPercent: Int,
    val critical: Boolean,
) : ActivityPayload

/** A transition worth telling the user about. */
sealed interface BatteryEvent {
    data class ChargingStarted(val reading: BatteryReading) : BatteryEvent
    data class ChargingStopped(val reading: BatteryReading) : BatteryEvent
    data class BecameFull(val reading: BatteryReading) : BatteryEvent
    data class Low(val levelPercent: Int, val critical: Boolean) : BatteryEvent
}

/**
 * Turns a stream of battery readings into discrete events. Pure and stateful: feed each reading in
 * order. Readings arrive on every 1 % change, so each rule is edge-triggered and fires once.
 */
class BatteryEventDetector(private val lowThresholdPercent: () -> Int) {
    private var previous: BatteryReading? = null
    private var announcedFull = false
    private var lowAnnounced = false
    private var criticalAnnounced = false

    fun onReading(r: BatteryReading): List<BatteryEvent> {
        val prev = previous
        previous = r
        val events = ArrayList<BatteryEvent>(2)

        if (prev != null) {
            if (!prev.pluggedIn && r.pluggedIn) {
                events += BatteryEvent.ChargingStarted(r)
                announcedFull = false
            }
            if (prev.pluggedIn && !r.pluggedIn) {
                events += BatteryEvent.ChargingStopped(r)
                announcedFull = false
            }
        }

        if (r.pluggedIn && r.full && !announcedFull) {
            // Only announce when we watched it become full; a full battery at boot is not news.
            if (prev != null && !prev.full) events += BatteryEvent.BecameFull(r)
            announcedFull = true
        }

        val threshold = lowThresholdPercent()
        if (r.pluggedIn) {
            lowAnnounced = false
            criticalAnnounced = false
        } else {
            if (r.levelPercent <= CRITICAL_PERCENT && !criticalAnnounced) {
                criticalAnnounced = true
                lowAnnounced = true
                events += BatteryEvent.Low(r.levelPercent, critical = true)
            } else if (r.levelPercent <= threshold && !lowAnnounced) {
                lowAnnounced = true
                events += BatteryEvent.Low(r.levelPercent, critical = false)
            }
            if (r.levelPercent > threshold + REARM_MARGIN) {
                lowAnnounced = false
                criticalAnnounced = false
            }
        }
        return events
    }

    companion object {
        const val CRITICAL_PERCENT = 5
        private const val REARM_MARGIN = 5
    }
}
