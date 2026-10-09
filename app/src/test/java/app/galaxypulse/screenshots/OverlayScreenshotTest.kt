package app.galaxypulse.screenshots

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.galaxypulse.artwork.ArtworkBundle
import app.galaxypulse.artwork.ArtworkProcessor
import app.galaxypulse.artwork.SampleCover
import app.galaxypulse.battery.BatteryAlertPayload
import app.galaxypulse.battery.ChargingEventType
import app.galaxypulse.battery.ChargingPayload
import app.galaxypulse.battery.PlugType
import app.galaxypulse.bluetooth.BluetoothPayload
import app.galaxypulse.bluetooth.ConnectionPhase
import app.galaxypulse.bluetooth.DeviceBattery
import app.galaxypulse.bluetooth.DeviceKind
import app.galaxypulse.engine.ActivityEntry
import app.galaxypulse.engine.ActivityKind
import app.galaxypulse.engine.ActivityPayload
import app.galaxypulse.media.MediaCustomAction
import app.galaxypulse.media.MediaSnapshot
import app.galaxypulse.media.PlaybackStatus
import app.galaxypulse.media.TransportCaps
import app.galaxypulse.notifications.NotificationActionLabel
import app.galaxypulse.notifications.NotificationPayload
import app.galaxypulse.overlay.DisplayInfo
import app.galaxypulse.overlay.GeometryPrefs
import app.galaxypulse.overlay.OverlayGeometry
import app.galaxypulse.overlay.PxRect
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.timers.ClockSample
import app.galaxypulse.timers.StopwatchMath
import app.galaxypulse.timers.StopwatchPayload
import app.galaxypulse.timers.StopwatchState
import app.galaxypulse.timers.TimerDonePayload
import app.galaxypulse.timers.TimerMath
import app.galaxypulse.timers.TimerPayload
import app.galaxypulse.timers.TimerState
import app.galaxypulse.ui.anim.MotionConfig
import app.galaxypulse.ui.design.GalaxyPulseTheme
import app.galaxypulse.ui.design.PulseType
import app.galaxypulse.ui.overlay.ActivitySizes
import app.galaxypulse.ui.overlay.BluetoothCardDimens
import app.galaxypulse.ui.overlay.BluetoothCardHost
import app.galaxypulse.ui.overlay.BluetoothCardModel
import app.galaxypulse.ui.overlay.DeviceIllustration
import app.galaxypulse.ui.overlay.NoOpOverlayActions
import app.galaxypulse.ui.overlay.OverlayUi
import app.galaxypulse.ui.overlay.PulseTopOverlay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Renders the real overlay composables (same code, same geometry maths as the running overlay) to PNGs
 * so layouts can be reviewed without a phone. Opt-in: `./gradlew testDebugUnitTest -Pscreenshots`.
 * Reduced motion is used so every frame is settled and deterministic.
 *
 * The fake cutout below is only an *input to the geometry maths* (so the pill is placed below a taller
 * cutout, as on real hardware); nothing resembling a camera is drawn.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h900dp-xxhdpi")
class OverlayScreenshotTest {

    @get:Rule
    val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val reduced = MotionConfig(reduced = true)

    private val covers = listOf(
        intArrayOf(0xFF1B2A6B.toInt(), 0xFF8B3DFF.toInt(), 0xFFFF6A88.toInt()),
        intArrayOf(0xFF3B2A0F.toInt(), 0xFFD9822B.toInt(), 0xFFF2E394.toInt()),
    )

    private fun artwork(index: Int = 0): ArtworkBundle = runBlocking {
        ArtworkProcessor(app).process("shot:$index", SampleCover.render(covers[index]), null)!!
    }

    private fun media(status: PlaybackStatus = PlaybackStatus.Paused, title: String = "Neon Skyline") = MediaSnapshot(
        packageName = "app.galaxypulse", appLabel = "Music", title = title, artist = "Aurora Drive", album = "Night Signals",
        status = status, reportedPositionMs = 62_000, reportedAtElapsedMs = 0, speed = 1f, durationMs = 215_000,
        caps = TransportCaps(true, true, true, true, true), artworkKey = "shot:0", outputLabel = "Galaxy Buds2 Pro",
        customActions = listOf(MediaCustomAction("shuffle", "Shuffle", 0, "app.galaxypulse")),
        upNext = "Midnight Drive",
    )

    private val clock = ClockSample(1_000_000, 1_700_000_000_000, 1)

    private fun notification(hidden: Boolean = false, call: Boolean = false) = NotificationPayload(
        "k", app.packageName, if (call) "Phone" else "Messages",
        if (hidden) null else if (call) "Jordan Lee" else "Alex",
        if (hidden) null else if (call) "Incoming call" else "Are we still on for 7? I booked the table by the window.",
        hidden,
        if (call) listOf(NotificationActionLabel(0, "Decline"), NotificationActionLabel(1, "Answer")) else listOf(NotificationActionLabel(0, "Mark as read")),
        true, call,
    )

    // ── Overlay states ────────────────────────────────────────────────────────────────────────

    @Test fun media_compact() = shoot("media_compact", ActivityKind.Media, media(), expanded = false, art = artwork())
    @Test fun media_compact_light() = shoot("media_compact_light", ActivityKind.Media, media(), expanded = false, art = artwork(), light = true)
    @Test fun media_expanded() = shoot("media_expanded", ActivityKind.Media, media(), expanded = true, art = artwork())
    @Test fun media_expanded_warm() = shoot("media_expanded_warm", ActivityKind.Media, media(title = "Golden Hour"), expanded = true, art = artwork(1))
    @Test fun media_expanded_light() = shoot("media_expanded_light", ActivityKind.Media, media(), expanded = true, art = artwork(), light = true)
    @Test fun media_expanded_no_art() = shoot("media_expanded_no_art", ActivityKind.Media, media(), expanded = true, art = null)
    @Test fun media_expanded_edge_off() = shoot(
        "media_expanded_edge_off", ActivityKind.Media, media(), expanded = true, art = artwork(),
        settings = PulseSettings(extendBackdropToTopEdge = false),
    )

    @Test fun charging_compact() = shoot("charging_compact", ActivityKind.Charging, ChargingPayload(ChargingEventType.Started, 72, PlugType.Ac, 32 * 60_000L, 31f), false)
    @Test fun charging_expanded() = shoot("charging_expanded", ActivityKind.Charging, ChargingPayload(ChargingEventType.Started, 72, PlugType.Ac, 32 * 60_000L, 31f), true)
    @Test fun battery_alert_expanded() = shoot("battery_alert_expanded", ActivityKind.BatteryAlert, BatteryAlertPayload(14, false), true)

    @Test fun timer_compact() = shoot("timer_compact", ActivityKind.Timer, TimerPayload(TimerMath.pause(TimerMath.start(TimerState(), 272_000, clock), clock), 1), false)
    @Test fun timer_expanded() = shoot("timer_expanded", ActivityKind.Timer, TimerPayload(TimerMath.pause(TimerMath.start(TimerState(), 272_000, clock), clock), 1), true)
    @Test fun timer_done_expanded() = shoot("timer_done_expanded", ActivityKind.TimerDone, TimerDonePayload(300_000), true)
    @Test fun stopwatch_expanded() = shoot(
        "stopwatch_expanded", ActivityKind.Stopwatch,
        StopwatchPayload(StopwatchMath.pause(StopwatchMath.start(StopwatchState(lapTotalsMs = listOf(12_400, 31_900, 40_100)), clock), clock), 1), true,
    )

    @Test fun notification_compact() = shoot("notification_compact", ActivityKind.Notification, notification(), false)
    @Test fun notification_expanded() = shoot("notification_expanded", ActivityKind.Notification, notification(), true)
    @Test fun notification_private_expanded() = shoot("notification_private_expanded", ActivityKind.Notification, notification(hidden = true), true)
    @Test fun call_expanded() = shoot("call_expanded", ActivityKind.Call, notification(call = true), true)

    @Test fun bluetooth_compact() = shoot("bluetooth_compact", ActivityKind.Bluetooth, buds(), false)
    @Test fun bluetooth_expanded() = shoot("bluetooth_expanded", ActivityKind.Bluetooth, buds(), true)

    private fun buds(phase: ConnectionPhase = ConnectionPhase.Connected) =
        BluetoothPayload("AA", "Galaxy Buds2 Pro", DeviceKind.Earbuds, phase, DeviceBattery(left = 82, right = 78, case = 91))

    // ── Bottom Bluetooth card ─────────────────────────────────────────────────────────────────

    @Test fun bluetooth_card_discovered() = card("bluetooth_card_discovered", buds(ConnectionPhase.Discovered).copy(battery = DeviceBattery()))
    @Test fun bluetooth_card_connected() = card("bluetooth_card_connected", buds())
    @Test fun bluetooth_card_failed() = card("bluetooth_card_failed", buds(ConnectionPhase.Failed).copy(battery = DeviceBattery()))

    @Test
    fun device_illustrations() {
        rule.setContent {
            GalaxyPulseTheme {
                Row(
                    Modifier.size(411.dp, 130.dp).background(Color(0xFF0A0B12)).padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(DeviceKind.Headphones, DeviceKind.Earbuds, DeviceKind.Speaker, DeviceKind.Watch, DeviceKind.Car).forEach {
                        DeviceIllustration(it, Modifier.size(64.dp))
                    }
                }
            }
        }
        save("device_illustrations")
    }

    // ── Harness ───────────────────────────────────────────────────────────────────────────────

    private fun shoot(
        name: String, kind: ActivityKind, payload: ActivityPayload, expanded: Boolean,
        art: ArtworkBundle? = null, light: Boolean = false, settings: PulseSettings = PulseSettings(),
    ) {
        rule.setContent { GalaxyPulseTheme { Stage(kind, payload, expanded, art, light, settings) } }
        save(name)
    }

    @Composable
    private fun Stage(kind: ActivityKind, payload: ActivityPayload, expanded: Boolean, art: ArtworkBundle?, light: Boolean, settings: PulseSettings) {
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.size(411.dp, 470.dp).background(if (light) Color(0xFFEDEFF6) else Color(0xFF12141F))) {
            val d = density.density
            val w = (411 * d).roundToInt()
            val h = (470 * d).roundToInt()
            // A centred hole taller than the status bar: only an input to the geometry, never drawn.
            val hole = PxRect(((411 / 2f - 14) * d).roundToInt(), (8 * d).roundToInt(), ((411 / 2f + 14) * d).roundToInt(), (36 * d).roundToInt())
            val display = DisplayInfo(w, h, d, statusBarBottomPx = (26 * d).roundToInt(), cutoutRects = listOf(hole))
            val layout = OverlayGeometry.layout(
                display, GeometryPrefs(settings.sizeScale, settings.verticalOffsetDp, settings.horizontalOffsetDp, settings.extendBackdropToTopEdge),
                ActivitySizes.compact(kind), ActivitySizes.expanded(kind),
            )
            val entry = ActivityEntry("shot", kind, payload, rank = 1, revision = 1, postedAt = 0)
            PulseTopOverlay(
                OverlayUi(entry, expanded, layout, IntOffset.Zero, settings, reduced, art, bootCount = 1, density = d),
                NoOpOverlayActions, Modifier.fillMaxSize(),
            )
            val ink = if (light) Color(0xFF111322) else Color.White
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("9:41", style = PulseType.BodyCompact, color = ink)
                Text("5G  ▂▄▆  100%", style = PulseType.Label, color = ink)
            }
        }
    }

    private fun card(name: String, payload: BluetoothPayload) {
        rule.setContent {
            GalaxyPulseTheme {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(411.dp, (BluetoothCardDimens.CARD_HEIGHT_DP + 40).dp).background(Color(0xFF12141F)),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    BluetoothCardHost(BluetoothCardModel(payload, 1), reduced, NoOpOverlayActions, hapticsEnabled = false)
                }
            }
        }
        save(name)
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
