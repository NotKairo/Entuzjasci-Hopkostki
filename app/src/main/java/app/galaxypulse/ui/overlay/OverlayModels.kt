package app.galaxypulse.ui.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import app.galaxypulse.artwork.ArtworkBundle
import app.galaxypulse.engine.ActivityEntry
import app.galaxypulse.engine.ActivityKind
import app.galaxypulse.media.MediaCustomAction
import app.galaxypulse.overlay.OverlayLayout
import app.galaxypulse.overlay.SizeDp
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.ui.anim.MotionConfig
import app.galaxypulse.ui.design.PulseSizes

/** Everything the top pill needs to render one frame of state. Built by the engine, or by the in-app preview. */
@Immutable
data class OverlayUi(
    val front: ActivityEntry?,
    val expanded: Boolean,
    val layout: OverlayLayout,
    /** Top-left of the overlay window in screen pixels; content is positioned relative to it. */
    val windowOrigin: IntOffset,
    val settings: PulseSettings,
    val motion: MotionConfig,
    val artwork: ArtworkBundle?,
    val bootCount: Int,
    val density: Float,
    val debugText: String? = null,
)

sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data class SeekTo(val positionMs: Long) : MediaCommand
    data class Custom(val action: MediaCustomAction) : MediaCommand
    data object OpenApp : MediaCommand
}

sealed interface TimerCommand {
    data object Pause : TimerCommand
    data object Resume : TimerCommand
    data object Cancel : TimerCommand
    data class Add(val millis: Long) : TimerCommand
    data object DismissAlarm : TimerCommand
}

sealed interface StopwatchCommand {
    data object Start : StopwatchCommand
    data object Pause : StopwatchCommand
    data object Lap : StopwatchCommand
    data object Reset : StopwatchCommand
}

sealed interface BluetoothCommand {
    data class Connect(val address: String) : BluetoothCommand
    data object OpenSystemSettings : BluetoothCommand
    data object DismissCard : BluetoothCommand
}

/** Everything the overlay can ask the rest of the app to do. */
interface OverlayActions {
    fun setExpanded(key: String, expanded: Boolean)
    fun dismiss(key: String)
    fun collapse()
    fun media(command: MediaCommand)
    fun timer(command: TimerCommand)
    fun stopwatch(command: StopwatchCommand)
    fun openNotification(sbnKey: String)
    fun notificationAction(sbnKey: String, index: Int)
    fun bluetooth(command: BluetoothCommand)
}

object NoOpOverlayActions : OverlayActions {
    override fun setExpanded(key: String, expanded: Boolean) = Unit
    override fun dismiss(key: String) = Unit
    override fun collapse() = Unit
    override fun media(command: MediaCommand) = Unit
    override fun timer(command: TimerCommand) = Unit
    override fun stopwatch(command: StopwatchCommand) = Unit
    override fun openNotification(sbnKey: String) = Unit
    override fun notificationAction(sbnKey: String, index: Int) = Unit
    override fun bluetooth(command: BluetoothCommand) = Unit
}

/** Compact and expanded sizes per activity, in dp, before the user's size scale. */
object ActivitySizes {
    fun compact(kind: ActivityKind): SizeDp = SizeDp(
        width = when (kind) {
            ActivityKind.Media -> PulseSizes.CompactMediaWidth.value
            ActivityKind.Timer, ActivityKind.Stopwatch -> PulseSizes.CompactTimerWidth.value
            ActivityKind.Charging -> PulseSizes.CompactChargingWidth.value
            ActivityKind.Notification, ActivityKind.Call -> PulseSizes.CompactNotificationWidth.value
            ActivityKind.Bluetooth -> PulseSizes.CompactBluetoothWidth.value
            ActivityKind.BatteryAlert, ActivityKind.TimerDone -> PulseSizes.CompactAlertWidth.value
        },
        height = PulseSizes.CompactHeight.value,
    )

    fun expanded(kind: ActivityKind): SizeDp = SizeDp(
        width = PulseSizes.ExpandedWidth.value,
        height = when (kind) {
            ActivityKind.Media -> PulseSizes.ExpandedMediaHeight.value
            ActivityKind.Timer -> PulseSizes.ExpandedTimerHeight.value
            ActivityKind.Stopwatch -> PulseSizes.ExpandedStopwatchHeight.value
            ActivityKind.Charging -> PulseSizes.ExpandedChargingHeight.value
            ActivityKind.Notification, ActivityKind.Call -> PulseSizes.ExpandedNotificationHeight.value
            ActivityKind.Bluetooth -> PulseSizes.ExpandedBluetoothHeight.value
            ActivityKind.BatteryAlert, ActivityKind.TimerDone -> PulseSizes.ExpandedAlertHeight.value
        },
    )
}
