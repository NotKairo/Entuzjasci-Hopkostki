package app.flux.ui.app

import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.flux.AppGraph
import app.flux.artwork.ArtworkBundle
import app.flux.artwork.SampleCover
import app.flux.battery.BatteryAlertPayload
import app.flux.battery.ChargingEventType
import app.flux.battery.ChargingPayload
import app.flux.battery.PlugType
import app.flux.bluetooth.BluetoothPayload
import app.flux.bluetooth.ConnectionPhase
import app.flux.bluetooth.DeviceBattery
import app.flux.bluetooth.DeviceKind
import app.flux.core.ClockSource
import app.flux.engine.ActivityEntry
import app.flux.engine.ActivityKind
import app.flux.engine.ActivityPayload
import app.flux.media.MediaSnapshot
import app.flux.media.PlaybackStatus
import app.flux.media.TransportCaps
import app.flux.notifications.NotificationActionLabel
import app.flux.notifications.NotificationPayload
import app.flux.overlay.GeometryPrefs
import app.flux.overlay.OverlayGeometry
import app.flux.overlay.DisplayInfo
import app.flux.settings.MotionPreset
import app.flux.settings.OverlayTheme
import app.flux.settings.FluxSettings
import app.flux.settings.ReducedMotionMode
import app.flux.timers.ClockSample
import app.flux.timers.StopwatchMath
import app.flux.timers.StopwatchPayload
import app.flux.timers.StopwatchState
import app.flux.timers.TimerMath
import app.flux.timers.TimerPayload
import app.flux.timers.TimerState
import app.flux.ui.anim.MotionConfig
import app.flux.ui.design.FluxColors
import app.flux.ui.design.FluxType
import app.flux.ui.overlay.ActivitySizes
import app.flux.ui.overlay.MediaCommand
import app.flux.ui.overlay.NoOpOverlayActions
import app.flux.ui.overlay.OverlayActions
import app.flux.ui.overlay.OverlayUi
import app.flux.ui.overlay.FluxTopOverlay
import kotlin.math.roundToInt

private enum class Sample(val label: String) {
    Music("Music"), Charging("Charging"), Timer("Timer"), Stopwatch("Stopwatch"),
    Notification("Notification"), Call("Call"), Bluetooth("Headphones"), Battery("Low battery"),
}

private val previewCovers = listOf(
    intArrayOf(0xFF1B2A6B.toInt(), 0xFF8B3DFF.toInt(), 0xFFFF6A88.toInt()),
    intArrayOf(0xFF0F3B3A.toInt(), 0xFF1FA2A6.toInt(), 0xFFF2C94C.toInt()),
)
private val previewTitles = listOf("Neon Skyline" to "Aurora Drive", "Midnight Drive" to "The Lowlights")

@Composable
fun PreviewScreen(graph: AppGraph, settings: FluxSettings, handle: SettingsHandle) {
    // While a slider is being dragged the preview follows it live; the value is saved on release.
    var live by remember { mutableStateOf<FluxSettings?>(null) }
    val effective = live ?: settings

    var sample by rememberSaveable { mutableStateOf(Sample.Music) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var lightBackground by rememberSaveable { mutableStateOf(false) }

    ScreenColumn {
        ScreenTitle("Live preview", "The same overlay code that runs over your apps. Tap the pill to expand it.")

        ChoiceChips(Sample.entries.toList(), sample, { it.label }, { sample = it; expanded = false })

        PreviewStage(graph, effective, sample, expanded, lightBackground, onExpandedChange = { expanded = it })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(if (expanded) "Collapse" else "Expand", { expanded = !expanded }, Modifier.weight(1f))
            SecondaryButton(if (lightBackground) "Dark app behind" else "Light app behind", { lightBackground = !lightBackground }, Modifier.weight(1f))
        }
        Hint("Preview shows the status bar on top of the overlay, exactly as Android layers it on a real phone. The camera is not drawn.")

        SectionCard("Appearance") {
            Text("Theme", style = FluxType.Caption, color = FluxColors.OnSurfaceMuted)
            ChoiceChips(OverlayTheme.entries.toList(), settings.theme, { it.label }, { t -> handle.update { it.copy(theme = t) } })
            SettingSwitch(
                "Blend artwork to the top edge",
                "The expanded card's backdrop fades to black toward the top of the screen",
                settings.extendBackdropToTopEdge,
            ) { v -> handle.update { it.copy(extendBackdropToTopEdge = v) } }
            SettingSwitch("Show artist in the compact pill", null, settings.showArtistInCompact) { v -> handle.update { it.copy(showArtistInCompact = v) } }
        }

        SectionCard("Size & position") {
            SettingSlider(
                "Size", "${(effective.sizeScale * 100).roundToInt()}%", effective.sizeScale, 0.8f..1.3f,
                onLive = { v -> live = effective.copy(sizeScale = v) },
                onCommit = { v -> handle.update { it.copy(sizeScale = v) }; live = null },
            )
            SettingSlider(
                "Distance below the camera / status bar", "${effective.verticalOffsetDp} dp", effective.verticalOffsetDp.toFloat(), -8f..64f,
                onLive = { v -> live = effective.copy(verticalOffsetDp = v.roundToInt()) },
                onCommit = { v -> handle.update { it.copy(verticalOffsetDp = v.roundToInt()) }; live = null },
            )
            SettingSlider(
                "Horizontal shift", "${effective.horizontalOffsetDp} dp", effective.horizontalOffsetDp.toFloat(), -120f..120f,
                onLive = { v -> live = effective.copy(horizontalOffsetDp = v.roundToInt()) },
                onCommit = { v -> handle.update { it.copy(horizontalOffsetDp = v.roundToInt()) }; live = null },
            )
            SettingSwitch("Hide in landscape", "Avoids a pill beside the camera when the phone is rotated", settings.hideInLandscape) { v -> handle.update { it.copy(hideInLandscape = v) } }
            Hint("The pill is always placed below the real camera cutout and status bar, measured on your phone at runtime.")
        }

        SectionCard("Motion") {
            Text("Style", style = FluxType.Caption, color = FluxColors.OnSurfaceMuted)
            ChoiceChips(MotionPreset.entries.toList(), settings.motionPreset, { it.label }, { m -> handle.update { it.copy(motionPreset = m) } })
            Text(settings.motionPreset.blurb, style = FluxType.Caption, color = FluxColors.OnSurfaceFaint)
            SettingSlider(
                "Bounce intensity", "${(effective.animationIntensity * 100).roundToInt()}%", effective.animationIntensity, 0f..1.5f,
                onLive = { v -> live = effective.copy(animationIntensity = v) },
                onCommit = { v -> handle.update { it.copy(animationIntensity = v) }; live = null },
            )
            Text("Reduced motion", style = FluxType.Caption, color = FluxColors.OnSurfaceMuted)
            ChoiceChips(ReducedMotionMode.entries.toList(), settings.reducedMotion, { it.label }, { r -> handle.update { it.copy(reducedMotion = r) } })
            SettingSwitch("Haptic feedback", "Light taps on expand, controls and dismiss", settings.haptics) { v -> handle.update { it.copy(haptics = v) } }
        }
    }
}

@Composable
private fun PreviewStage(
    graph: AppGraph,
    settings: FluxSettings,
    sample: Sample,
    expanded: Boolean,
    lightBackground: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val context = LocalContext.current

    // Music state is local to the preview so the controls really work (including the artwork transition).
    var track by remember { mutableStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(62_000L) }
    var positionAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val artwork by produceState<ArtworkBundle?>(null, track) {
        value = graph.artwork.process("preview:$track", SampleCover.render(previewCovers[track]), null)
    }

    val boot = remember { ClockSource.bootCount(context) }
    val payload: Pair<ActivityKind, ActivityPayload> = remember(sample, track, playing, position, positionAt) {
        val now = SystemClock.elapsedRealtime()
        val clock = ClockSample(now, System.currentTimeMillis(), boot)
        when (sample) {
            Sample.Music -> ActivityKind.Media to MediaSnapshot(
                packageName = "preview", appLabel = "Music", title = previewTitles[track].first, artist = previewTitles[track].second,
                album = "", status = if (playing) PlaybackStatus.Playing else PlaybackStatus.Paused,
                reportedPositionMs = position, reportedAtElapsedMs = positionAt, speed = 1f, durationMs = 215_000L,
                caps = TransportCaps(true, true, true, true, true), artworkKey = "preview:$track", outputLabel = "Galaxy Buds2 Pro",
            )
            Sample.Charging -> ActivityKind.Charging to ChargingPayload(ChargingEventType.Started, 72, PlugType.Ac, 32 * 60_000L, 31f)
            Sample.Timer -> ActivityKind.Timer to TimerPayload(TimerMath.start(TimerState(), 272_000L, clock), boot)
            Sample.Stopwatch -> ActivityKind.Stopwatch to StopwatchPayload(
                StopwatchMath.start(StopwatchState(lapTotalsMs = listOf(12_400L, 31_900L)), clock), boot,
            )
            Sample.Notification -> ActivityKind.Notification to NotificationPayload(
                "preview", context.packageName, "Messages", "Alex", "Are we still on for 7? I booked the table by the window.",
                false, listOf(NotificationActionLabel(0, "Mark as read")), true, false,
            )
            Sample.Call -> ActivityKind.Call to NotificationPayload(
                "preview", context.packageName, "Phone", "Jordan Lee", "Incoming call", false,
                listOf(NotificationActionLabel(0, "Decline"), NotificationActionLabel(1, "Answer")), true, true,
            )
            Sample.Bluetooth -> ActivityKind.Bluetooth to BluetoothPayload(
                "preview", "Galaxy Buds2 Pro", DeviceKind.Earbuds, ConnectionPhase.Connected, DeviceBattery(left = 82, right = 78, case = 91),
            )
            Sample.Battery -> ActivityKind.BatteryAlert to BatteryAlertPayload(14, false)
        }
    }

    val actions = remember(onExpandedChange) {
        object : OverlayActions by NoOpOverlayActions {
            override fun setExpanded(key: String, expanded: Boolean) = onExpandedChange(expanded)
            override fun dismiss(key: String) = onExpandedChange(false)
            override fun collapse() = onExpandedChange(false)
            override fun media(command: MediaCommand) {
                val now = SystemClock.elapsedRealtime()
                val current = if (playing) position + (now - positionAt) else position
                when (command) {
                    MediaCommand.Play -> { position = current; positionAt = now; playing = true }
                    MediaCommand.Pause -> { position = current; positionAt = now; playing = false }
                    MediaCommand.Next, MediaCommand.Previous -> { track = (track + 1) % previewCovers.size; position = 0; positionAt = now; playing = true }
                    is MediaCommand.SeekTo -> { position = command.positionMs; positionAt = now }
                    else -> Unit
                }
            }
        }
    }

    val animationsOff = remember { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(440.dp)
            .clip(shape)
            .border(1.dp, FluxColors.StrokeStrong, shape)
            .background(if (lightBackground) Color(0xFFEDEFF6) else Color(0xFF12141F)),
    ) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val display = DisplayInfo(widthPx, heightPx, density.density, statusBarBottomPx = (26 * density.density).roundToInt())
        val layout = OverlayGeometry.layout(
            display,
            GeometryPrefs(settings.sizeScale, settings.verticalOffsetDp, settings.horizontalOffsetDp, settings.extendBackdropToTopEdge),
            ActivitySizes.compact(payload.first), ActivitySizes.expanded(payload.first),
        )
        val entry = remember(payload) {
            ActivityEntry("preview", payload.first, payload.second, rank = 1, revision = 1, postedAt = 0)
        }

        FakeAppContent(light = lightBackground)
        FluxTopOverlay(
            ui = OverlayUi(
                front = entry, expanded = expanded, layout = layout, windowOrigin = IntOffset.Zero, settings = settings,
                motion = MotionConfig.from(settings, animationsOff), artwork = if (sample == Sample.Music) artwork else null,
                bootCount = boot, density = density.density,
            ),
            actions = actions,
            modifier = Modifier.fillMaxSize(),
        )
        MockStatusBar(light = lightBackground)
    }
}

/** Placeholder "app" so the pill is judged against real-looking content, not an empty box. */
@Composable
private fun FakeAppContent(light: Boolean) {
    Canvas(Modifier.fillMaxSize()) {
        val ink = if (light) Color(0xFF1B1E2B) else Color(0xFFE9ECF8)
        val cardColor = ink.copy(alpha = if (light) 0.07f else 0.07f)
        var y = 120.dp.toPx()
        repeat(5) { i ->
            drawRoundRect(cardColor, Offset(16.dp.toPx(), y), androidx.compose.ui.geometry.Size(size.width - 32.dp.toPx(), 56.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
            drawRoundRect(ink.copy(alpha = 0.18f), Offset(32.dp.toPx(), y + 16.dp.toPx()), androidx.compose.ui.geometry.Size((120 + i * 18).dp.toPx(), 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawRoundRect(ink.copy(alpha = 0.10f), Offset(32.dp.toPx(), y + 32.dp.toPx()), androidx.compose.ui.geometry.Size(180.dp.toPx(), 6.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            y += 68.dp.toPx()
        }
    }
}

/** The system draws its status bar *above* app overlays; the preview does the same. No camera shape. */
@Composable
private fun MockStatusBar(light: Boolean) {
    val ink = if (light) Color(0xFF111322) else Color.White
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("9:41", style = FluxType.BodyCompact, color = ink)
        Text("5G  ▂▄▆  100%", style = FluxType.Label, color = ink)
    }
}
