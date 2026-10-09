package app.flux.bluetooth

import app.flux.engine.ActivityPayload

enum class DeviceKind { Earbuds, Headphones, Speaker, Watch, Car, Other }

enum class ConnectionPhase {
    /** Found by an in-app scan, not paired yet: the card offers Connect. */
    Discovered,
    /** createBond() issued; Android shows its own pairing dialog if it needs one. */
    Pairing,
    Connecting,
    Connected,
    /** Paired, but the audio profile did not come up in time: point the user at system settings. */
    PairedNotConnected,
    Failed,
}

/** Only fields the system actually reported are non-null; the UI hides everything else. */
data class DeviceBattery(
    val single: Int? = null,
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
) {
    val isEmpty: Boolean get() = single == null && left == null && right == null && case == null
}

data class BluetoothPayload(
    val address: String,
    val name: String,
    val kind: DeviceKind,
    val phase: ConnectionPhase,
    val battery: DeviceBattery = DeviceBattery(),
) : ActivityPayload

/**
 * Classifies a device from its Bluetooth class and name. Numeric values are the public constants of
 * `android.bluetooth.BluetoothClass.Device` / `.Major`, kept as literals so this stays JVM-testable.
 */
object DeviceClassifier {
    private const val MAJOR_AUDIO_VIDEO = 0x0400
    private const val MAJOR_WEARABLE = 0x0700

    private const val AV_WEARABLE_HEADSET = 0x0404
    private const val AV_HANDSFREE = 0x0408
    private const val AV_LOUDSPEAKER = 0x0414
    private const val AV_HEADPHONES = 0x0418
    private const val AV_PORTABLE_AUDIO = 0x041C
    private const val AV_CAR_AUDIO = 0x0420
    private const val AV_HIFI_AUDIO = 0x0428
    private const val WEARABLE_WRIST_WATCH = 0x0704

    private val earbudWords = listOf("buds", "airpods", "pods", "earbud", "earphone", "ear (", "tws")
    private val watchWords = listOf("watch", "band", "fit")

    fun classify(majorClass: Int?, deviceClass: Int?, name: String?): DeviceKind {
        val n = name?.lowercase().orEmpty()
        if (earbudWords.any { it in n }) return DeviceKind.Earbuds
        when (deviceClass) {
            AV_HEADPHONES, AV_WEARABLE_HEADSET -> return DeviceKind.Headphones
            AV_LOUDSPEAKER, AV_PORTABLE_AUDIO, AV_HIFI_AUDIO -> return DeviceKind.Speaker
            AV_CAR_AUDIO -> return DeviceKind.Car
            WEARABLE_WRIST_WATCH -> return DeviceKind.Watch
            AV_HANDSFREE -> return if (n.contains("car")) DeviceKind.Car else DeviceKind.Headphones
        }
        if (majorClass == MAJOR_WEARABLE && watchWords.any { it in n }) return DeviceKind.Watch
        if (majorClass == MAJOR_AUDIO_VIDEO) return DeviceKind.Headphones
        if (watchWords.any { n.startsWith(it) || " $it" in n }) return DeviceKind.Watch
        return DeviceKind.Other
    }

    /** Devices worth a connection card by default: audio gear and watches. Keyboards, TVs etc. are ignored. */
    fun isRelevant(majorClass: Int?, deviceClass: Int?, name: String?): Boolean =
        classify(majorClass, deviceClass, name) != DeviceKind.Other
}

/**
 * Parses the one battery report that a supported public broadcast delivers:
 * `BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT` carrying `+IPHONEACCEV`
 * (args = [count, key1, value1, key2, value2, …]; key 1 is battery on a 0..9 scale).
 * Many headsets send this regardless of vendor. Returns percent, or null when absent/unparseable.
 */
object HeadsetBatteryParser {
    fun parseIphoneAccev(args: List<Any?>): Int? {
        val count = (args.firstOrNull() as? Number)?.toInt() ?: return null
        var i = 1
        repeat(count) {
            val key = (args.getOrNull(i) as? Number)?.toInt()
            val value = (args.getOrNull(i + 1) as? Number)?.toInt()
            if (key == BATTERY_KEY && value != null && value in 0..9) return (value + 1) * 10
            i += 2
        }
        return null
    }

    private const val BATTERY_KEY = 1
}
