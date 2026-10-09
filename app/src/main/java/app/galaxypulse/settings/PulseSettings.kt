package app.galaxypulse.settings

import app.galaxypulse.engine.ActivityKind
import app.galaxypulse.engine.PriorityConfig

enum class OverlayTheme(val label: String) {
    ArtworkTint("Artwork tint"),
    Midnight("Midnight blue"),
    Violet("Violet"),
}

enum class MotionPreset(val label: String, val blurb: String) {
    Calm("Calm", "Soft, almost no overshoot"),
    Balanced("Balanced", "One UI-like, tiny settle"),
    Lively("Lively", "Springy, more squash and stretch"),
}

enum class ReducedMotionMode(val label: String) {
    FollowSystem("Follow system"),
    On("Always reduce"),
    Off("Never reduce"),
}

enum class NotificationContentMode(val label: String, val blurb: String) {
    Full("Show previews", "Title and text are shown; a notification marked private is still hidden while locked"),
    HideWhenLocked("Hide while locked", "App name only while the device is locked (default)"),
    AppOnly("App name only", "Never show message text"),
}

/**
 * User-orderable priority groups. Calls, timer alarms and critical battery alerts always outrank
 * these; the order below decides everything else. Timers and stopwatch share one slot.
 */
enum class PriorityGroup(val label: String, val kinds: List<ActivityKind>) {
    Timers("Timer & stopwatch", listOf(ActivityKind.Timer, ActivityKind.Stopwatch)),
    Bluetooth("Bluetooth", listOf(ActivityKind.Bluetooth)),
    Charging("Charging", listOf(ActivityKind.Charging)),
    Media("Music", listOf(ActivityKind.Media)),
    Notifications("Notifications", listOf(ActivityKind.Notification)),
}

data class PulseSettings(
    // Master
    val overlayEnabled: Boolean = false,
    val startOnBoot: Boolean = true,

    // Layout
    val sizeScale: Float = 1f,
    val verticalOffsetDp: Int = 0,
    val horizontalOffsetDp: Int = 0,
    val extendBackdropToTopEdge: Boolean = true,
    val hideInLandscape: Boolean = true,

    // Appearance
    val theme: OverlayTheme = OverlayTheme.ArtworkTint,
    val showArtistInCompact: Boolean = true,

    // Motion
    val motionPreset: MotionPreset = MotionPreset.Balanced,
    /** 0 = no bounce at all, 1 = preset as designed, 1.5 = extra springy. */
    val animationIntensity: Float = 1f,
    val reducedMotion: ReducedMotionMode = ReducedMotionMode.FollowSystem,
    val haptics: Boolean = true,

    // Gestures
    val tapToExpand: Boolean = true,
    val swipeToDismiss: Boolean = true,
    val tapOutsideCollapses: Boolean = true,

    // Timeouts (seconds)
    val pausedHideDelaySec: Int = 8,
    val notificationSec: Int = 5,
    val chargingSec: Int = 4,
    val bluetoothSec: Int = 6,
    val batteryAlertSec: Int = 8,

    // Events
    val enableMedia: Boolean = true,
    val enableNotifications: Boolean = true,
    val enableCharging: Boolean = true,
    val enableBluetooth: Boolean = true,
    val enableTimers: Boolean = true,
    val enableLowBattery: Boolean = true,
    val lowBatteryThresholdPercent: Int = 15,
    val priorityOrder: List<PriorityGroup> = DEFAULT_PRIORITY_ORDER,

    // Notifications
    val notificationContent: NotificationContentMode = NotificationContentMode.HideWhenLocked,
    val includeSilentNotifications: Boolean = false,
    val blockedNotificationApps: Set<String> = emptySet(),
    val hideContentApps: Set<String> = emptySet(),
    val ignoredMediaApps: Set<String> = emptySet(),

    // Bluetooth
    val bluetoothBottomCard: Boolean = true,
    val bluetoothCooldownSec: Int = 30,
    val mutedBluetoothDevices: Set<String> = emptySet(),

    // Diagnostics
    val debugHud: Boolean = false,
) {
    /** Rank table for the scheduler, derived from the user's chosen order. */
    fun priorityConfig(): PriorityConfig {
        val ranks = HashMap<ActivityKind, Int>()
        priorityOrder.forEachIndexed { index, group ->
            group.kinds.forEach { ranks[it] = FIRST_USER_RANK + index }
        }
        return PriorityConfig(ranks)
    }

    companion object {
        /** Calls (1), timer alarms and battery alerts (2) always come first. */
        const val FIRST_USER_RANK = 3

        val DEFAULT_PRIORITY_ORDER = listOf(
            PriorityGroup.Timers,
            PriorityGroup.Bluetooth,
            PriorityGroup.Charging,
            PriorityGroup.Media,
            PriorityGroup.Notifications,
        )
    }
}
