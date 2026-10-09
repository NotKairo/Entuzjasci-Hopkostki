package app.flux.ui.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.IntOffset
import app.flux.artwork.ArtworkBundle
import app.flux.engine.ActivityEntry
import app.flux.engine.ActivityKind
import app.flux.media.MediaCustomAction
import app.flux.overlay.OverlayLayout
import app.flux.overlay.SizeDp
import app.flux.settings.FluxSettings
import app.flux.ui.anim.MotionConfig
import app.flux.ui.design.FluxSizes

/** Everything the top pill needs to render one frame of state. Built by the engine, or by the in-app preview. */
@Immutable
data class OverlayUi(
    val front: ActivityEntry?,
    val expanded: Boolean,
    val layout: OverlayLayout,
    /** Top-left of the overlay window in screen pixels; content is positioned relative to it. */
    val windowOrigin: IntOffset,
    val settings: FluxSettings,
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

    /** True while the user is dragging the pill sideways, so the host can widen the window for the travel. */
    fun setDragging(active: Boolean) = Unit
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
            ActivityKind.Media -> FluxSizes.CompactMediaWidth.value
            ActivityKind.Timer, ActivityKind.Stopwatch -> FluxSizes.CompactTimerWidth.value
            ActivityKind.Charging -> FluxSizes.CompactChargingWidth.value
            ActivityKind.Notification, ActivityKind.Call -> FluxSizes.CompactNotificationWidth.value
            ActivityKind.Bluetooth -> FluxSizes.CompactBluetoothWidth.value
            ActivityKind.BatteryAlert, ActivityKind.TimerDone -> FluxSizes.CompactAlertWidth.value
        },
        height = FluxSizes.CompactHeight.value,
    )

    fun expanded(kind: ActivityKind): SizeDp = SizeDp(
        width = FluxSizes.ExpandedWidth.value,
        height = when (kind) {
            ActivityKind.Media -> FluxSizes.ExpandedMediaHeight.value
            ActivityKind.Timer -> FluxSizes.ExpandedTimerHeight.value
            ActivityKind.Stopwatch -> FluxSizes.ExpandedStopwatchHeight.value
            ActivityKind.Charging -> FluxSizes.ExpandedChargingHeight.value
            ActivityKind.Notification, ActivityKind.Call -> FluxSizes.ExpandedNotificationHeight.value
            ActivityKind.Bluetooth -> FluxSizes.ExpandedBluetoothHeight.value
            ActivityKind.BatteryAlert, ActivityKind.TimerDone -> FluxSizes.ExpandedAlertHeight.value
        },
    )
}
