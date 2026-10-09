package app.galaxypulse.ui.app

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.galaxypulse.AppGraph
import app.galaxypulse.BuildConfig
import app.galaxypulse.core.Permissions
import app.galaxypulse.engine.SimulatedEvent
import app.galaxypulse.overlay.DisplayInfoReader
import app.galaxypulse.overlay.GeometryPrefs
import app.galaxypulse.overlay.OverlayGeometry
import app.galaxypulse.overlay.SizeDp
import app.galaxypulse.settings.PulseSettings
import app.galaxypulse.ui.anim.AnimationTracker
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseType
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(graph: AppGraph, settings: PulseSettings, handle: SettingsHandle) {
    val context = LocalContext.current
    var perms by remember { mutableStateOf(Permissions.read(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms = Permissions.read(context) }

    val running by graph.serviceRunning.collectAsStateWithLifecycle()
    val log by graph.diagnostics.lines.collectAsStateWithLifecycle()
    val scheduler by graph.engine.state.collectAsStateWithLifecycle()
    val mediaAccess by graph.media.access.collectAsStateWithLifecycle()

    // Poll the animation counter only while this screen is visible.
    var activeAnimations by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            activeAnimations = AnimationTracker.activeAnimations
            delay(500)
        }
    }

    ScreenColumn {
        ScreenTitle("Test & diagnostics", "Fire test events through the real pipeline, and check what Pulse knows about this phone")

        SectionCard("Send test events") {
            if (!running) Hint("Turn the overlay on (Setup tab) first — test events are drawn by the running overlay.")
            SimulatedEvent.entries.groupBy { it.group }.forEach { (group, events) ->
                Text(group, style = PulseType.Caption, color = PulseColors.OnSurfaceMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    events.forEach { event ->
                        AssistChip(
                            onClick = { graph.engine.simulate(event) },
                            label = { Text(event.label, style = PulseType.Caption) },
                            colors = AssistChipDefaults.assistChipColors(labelColor = PulseColors.OnSurface),
                        )
                    }
                }
            }
            Hint("The Bluetooth pairing card's Connect button plays a full simulated Pairing → Connecting → Connected sequence.")
        }

        SectionCard("Idle check") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Overlay service", style = PulseType.Body, color = PulseColors.OnSurface)
                StatusPill(if (running) "Running" else "Stopped", good = running)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Showing now", style = PulseType.Body, color = PulseColors.OnSurface)
                Text(scheduler.frontKey ?: "nothing", style = PulseType.Body, color = PulseColors.OnSurfaceMuted)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Running animations", style = PulseType.Body, color = PulseColors.OnSurface)
                Text("$activeAnimations", style = PulseType.Numeric, color = if (activeAnimations == 0) PulseColors.Charging else PulseColors.OnSurfaceMuted)
            }
            Hint("When nothing is showing, the overlay window is removed entirely: no view, no composition, no frame callbacks. Running animations should read 0 a moment after any motion ends.")
        }

        SectionCard("Permissions & capabilities") {
            StatusRow("Display over other apps", perms.overlay)
            StatusRow("Notification access", perms.notificationAccess)
            StatusRow("Media sessions visible", mediaAccess == app.galaxypulse.media.MediaAccess.Granted)
            StatusRow("Post notifications", perms.postNotifications)
            StatusRow("Bluetooth connect", perms.bluetoothConnect)
            StatusRow("Bluetooth scan", perms.bluetoothScan)
            StatusRow("Exact alarms", perms.exactAlarms)
            StatusRow("Battery unrestricted", perms.batteryUnrestricted)
        }

        SectionCard("Display geometry (read from Android)") {
            val info = remember(perms) { DisplayInfoReader.read(context) }
            val layout = remember(info, settings) {
                OverlayGeometry.layout(
                    info,
                    GeometryPrefs(settings.sizeScale, settings.verticalOffsetDp, settings.horizontalOffsetDp, settings.extendBackdropToTopEdge),
                    SizeDp(236f, 40f), SizeDp(388f, 214f),
                )
            }
            Mono(DisplayInfoReader.describe(info))
            Mono("compact pill: x ${layout.compact.left}..${layout.compact.right}, y ${layout.compact.top}..${layout.compact.bottom} px")
            Mono("expanded card: ${layout.expanded.width}×${layout.expanded.height} px, backdrop starts at y ${layout.backdropTopPx}")
            Hint("The pill's top is always at or below the larger of the status-bar height and the cutout's bottom edge. The camera itself is never drawn.")
        }

        SectionCard("Device") {
            Mono("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            Mono("Galaxy Pulse ${BuildConfig.VERSION_NAME}")
        }

        SectionCard("Event log") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton("Clear", { graph.diagnostics.clear() })
            }
            if (log.isEmpty()) Text("No events yet.", style = PulseType.Caption, color = PulseColors.OnSurfaceFaint)
            log.take(60).forEach { Mono(it) }
        }

        AboutSection()
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = PulseType.Body, color = PulseColors.OnSurface)
        StatusPill(if (ok) "Yes" else "No", good = ok)
    }
}

@Composable
private fun Mono(text: String) {
    Text(text, style = PulseType.Caption.copy(fontFamily = FontFamily.Monospace), color = PulseColors.OnSurfaceMuted)
}

@Composable
private fun AboutSection() {
    SectionCard("About & licenses") {
        Text(
            "Galaxy Pulse is an independent app. It uses public Android APIs only and contains no code, artwork or assets from the projects it studied.",
            style = PulseType.Caption, color = PulseColors.OnSurfaceMuted,
        )
        Text("Concept credit", style = PulseType.Caption, color = PulseColors.OnSurfaceMuted)
        Text(
            "The media-first, island-style overlay idea is shared with DynamicIslandMusic by Bryan Guerra (@bguerraDev) and other Android \"dynamic island\" projects (dynamic-island-android, OmniLand, bt-popup). No source from them was used.",
            style = PulseType.Caption, color = PulseColors.OnSurface,
        )
        Text("Third-party libraries", style = PulseType.Caption, color = PulseColors.OnSurfaceMuted)
        Text(
            "Lottie for Android — Apache-2.0, © Airbnb.\nJetpack Compose, AndroidX (Core, Activity, Lifecycle, DataStore, Palette) — Apache-2.0, © Google.\nKotlin and kotlinx.coroutines — Apache-2.0, © JetBrains.\nFull notices: THIRD_PARTY_NOTICES.md in the source repository.",
            style = PulseType.Caption, color = PulseColors.OnSurface,
        )
    }
}
