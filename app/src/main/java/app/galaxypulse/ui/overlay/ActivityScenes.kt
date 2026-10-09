package app.galaxypulse.ui.overlay

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.galaxypulse.battery.BatteryAlertPayload
import app.galaxypulse.battery.ChargingEventType
import app.galaxypulse.battery.ChargingPayload
import app.galaxypulse.bluetooth.BluetoothPayload
import app.galaxypulse.bluetooth.ConnectionPhase
import app.galaxypulse.bluetooth.DeviceBattery
import app.galaxypulse.bluetooth.DeviceKind
import app.galaxypulse.notifications.NotificationPayload
import app.galaxypulse.timers.ClockSample
import app.galaxypulse.timers.StopwatchMath
import app.galaxypulse.timers.StopwatchPayload
import app.galaxypulse.timers.StopwatchPhase
import app.galaxypulse.timers.TimeFormat
import app.galaxypulse.timers.TimerDonePayload
import app.galaxypulse.timers.TimerMath
import app.galaxypulse.timers.TimerPayload
import app.galaxypulse.timers.TimerPhase
import app.galaxypulse.ui.anim.MotionConfig
import app.galaxypulse.ui.anim.PulseHaptics
import app.galaxypulse.ui.anim.rememberTick
import app.galaxypulse.ui.design.PulseColors
import app.galaxypulse.ui.design.PulseIcon
import app.galaxypulse.ui.design.PulseIconView
import app.galaxypulse.ui.design.PulseType

private val Muted = PulseColors.OnSurfaceMuted
private val Faint = PulseColors.OnSurfaceFaint

/**
 * Scaffold for every non-music scene: a compact layer and an expanded layer, cross-revealed on the
 * pill's morph progress. Each layer is laid out at the content size given by [SceneMetrics], and the
 * expanded layer is only composed once the shape can hold it.
 */
@Composable
internal fun TwoLayerScene(
    m: SceneMetrics,
    compact: @Composable BoxScope.() -> Unit,
    expanded: @Composable BoxScope.() -> Unit,
) {
    val showExpanded by remember(m) { derivedStateOf { m.progress > 0.25f } }
    val showCompact by remember(m) { derivedStateOf { m.progress < 0.75f } }
    Box(Modifier.fillMaxSize()) {
        if (showCompact) {
            Box(
                Modifier
                    .placedFree { Rect(m.compactLeft, m.offsetY, m.compactLeft + m.dims.compactW, m.offsetY + m.dims.compactH) }
                    .graphicsLayer { alpha = 1f - PillShapeMath.smoothstep(0f, 0.4f, m.progress) },
                content = compact,
            )
        }
        if (showExpanded) {
            Box(
                Modifier
                    .placedFree { Rect(m.expandedLeft, m.offsetY, m.expandedLeft + m.dims.expandedW, m.offsetY + m.dims.expandedH) }
                    .graphicsLayer {
                        val reveal = PillShapeMath.smoothstep(0.55f, 1f, m.progress)
                        alpha = reveal
                        translationY = (1f - reveal) * m.dp(8f)
                    },
                content = expanded,
            )
        }
    }
}

// ── Notification & call ───────────────────────────────────────────────────────────────────────

@Composable
internal fun NotificationScene(
    m: SceneMetrics,
    p: NotificationPayload,
    actions: OverlayActions,
    haptics: PulseHaptics,
) {
    TwoLayerScene(
        m,
        compact = {
            Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(p.packageName, p.appLabel, 24.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    val primary = if (p.contentHidden) p.appLabel else (p.title ?: p.appLabel)
                    val secondary = when {
                        p.contentHidden -> "New notification"
                        p.text != null -> p.text
                        p.title != null -> p.appLabel
                        else -> null
                    }
                    PText(primary, PulseType.BodyCompact)
                    if (secondary != null) PText(secondary, PulseType.Label, color = Muted)
                }
            }
        },
        expanded = {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(p.packageName, p.appLabel, 22.dp)
                    Spacer(Modifier.width(8.dp))
                    PText(p.appLabel, PulseType.Label, Modifier.weight(1f), color = Muted)
                    PText("now", PulseType.Label, color = Faint)
                }
                Spacer(Modifier.height(8.dp))
                if (p.contentHidden) {
                    PText("New notification", PulseType.Title)
                } else {
                    p.title?.let { PText(it, PulseType.Title) }
                    p.text?.let { PText(it, PulseType.Body, color = Muted, maxLines = 2) }
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    p.actions.take(2).forEach { action ->
                        PulseTextButton(action.label, onClick = { haptics.tap(); actions.notificationAction(p.sbnKey, action.index) })
                    }
                    if (p.hasContentIntent) {
                        PulseTextButton("Open", emphasized = true, onClick = { haptics.tap(); actions.openNotification(p.sbnKey) })
                    }
                }
            }
        },
    )
}

@Composable
internal fun CallScene(
    m: SceneMetrics,
    p: NotificationPayload,
    actions: OverlayActions,
    haptics: PulseHaptics,
) {
    val caller = if (p.contentHidden) p.appLabel else (p.title ?: p.appLabel)
    TwoLayerScene(
        m,
        compact = {
            Row(Modifier.fillMaxSize().padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(p.packageName, p.appLabel, 24.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    PText(caller, PulseType.BodyCompact)
                    PText("Incoming call", PulseType.Label, color = PulseColors.Charging)
                }
            }
        },
        expanded = {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(p.packageName, p.appLabel, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        PText(caller, PulseType.Title)
                        PText("Incoming call", PulseType.Body, color = Muted)
                    }
                }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The dialer decides what actions exist (decline / answer); we never invent one.
                    p.actions.take(2).forEachIndexed { i, action ->
                        val isAnswer = i == p.actions.take(2).lastIndex && p.actions.size > 1 || p.actions.size == 1
                        PulseTextButton(
                            action.label,
                            emphasized = isAnswer,
                            accent = PulseColors.Charging,
                            modifier = Modifier.weight(1f),
                            onClick = { haptics.tap(); actions.notificationAction(p.sbnKey, action.index) },
                        )
                    }
                    if (p.actions.isEmpty() && p.hasContentIntent) {
                        PulseTextButton("Open", emphasized = true, modifier = Modifier.weight(1f), onClick = { actions.openNotification(p.sbnKey) })
                    }
                }
            }
        },
    )
}

// ── Charging & battery ────────────────────────────────────────────────────────────────────────

@Composable
private fun PulsingIcon(icon: PulseIcon, color: Color, size: Dp, pulse: Boolean, motion: MotionConfig) {
    if (pulse && !motion.reduced) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val pulseAlpha = transition.animateFloat(
            0.5f, 1f,
            infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse-alpha",
        )
        Box(Modifier.graphicsLayer { alpha = pulseAlpha.value }) { PulseIconView(icon, color, size = size) }
    } else {
        PulseIconView(icon, color, size = size)
    }
}

@Composable
internal fun ChargingScene(m: SceneMetrics, p: ChargingPayload, motion: MotionConfig) {
    val title = when (p.type) {
        ChargingEventType.Started -> "Charging"
        ChargingEventType.Disconnected -> "Unplugged"
        ChargingEventType.Full -> "Fully charged"
    }
    val accent = if (p.type == ChargingEventType.Disconnected) Muted else PulseColors.Charging
    TwoLayerScene(
        m,
        compact = {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                PulsingIcon(PulseIcon.Bolt, accent, 18.dp, pulse = p.type == ChargingEventType.Started, motion = motion)
                Spacer(Modifier.width(7.dp))
                PText("${p.levelPercent}%", PulseType.NumericCompact)
                Spacer(Modifier.width(7.dp))
                PText(title, PulseType.Label, color = Muted)
            }
        },
        expanded = {
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(p.levelPercent / 100f, accent, Modifier.fillMaxSize(), strokeWidth = 7.dp)
                    Row(verticalAlignment = Alignment.Bottom) {
                        PText("${p.levelPercent}", PulseType.Title.copy(fontSize = 22.sp))
                        PText("%", PulseType.Caption, color = Muted, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PText(title, PulseType.Title)
                    // Only fields Android actually reported are shown.
                    p.plug?.let { PText(it.label, PulseType.Body, color = Muted) }
                    p.timeToFullMs?.takeIf { p.type == ChargingEventType.Started }?.let {
                        PText("About ${TimeFormat.approximate(it)} to full", PulseType.Body, color = Muted)
                    }
                    p.temperatureC?.let { PText("%.0f °C".format(java.util.Locale.ROOT, it), PulseType.Caption, color = Faint) }
                }
            }
        },
    )
}

@Composable
internal fun BatteryAlertScene(m: SceneMetrics, p: BatteryAlertPayload) {
    val color = if (p.critical) PulseColors.Critical else PulseColors.Warning
    val title = if (p.critical) "Battery critically low" else "Battery low"
    TwoLayerScene(
        m,
        compact = {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                ProgressRing(p.levelPercent / 100f, color, Modifier.size(20.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(9.dp))
                PText("Battery ${p.levelPercent}%", PulseType.BodyCompact)
            }
        },
        expanded = {
            Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(p.levelPercent / 100f, color, Modifier.fillMaxSize(), strokeWidth = 7.dp)
                    PText("${p.levelPercent}%", PulseType.Numeric)
                }
                Spacer(Modifier.width(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PText(title, PulseType.Title, color = color)
                    PText("Connect a charger soon", PulseType.Body, color = Muted)
                }
            }
        },
    )
}

// ── Timer ─────────────────────────────────────────────────────────────────────────────────────

private const val FINAL_COUNTDOWN_MS = 10_000L

@Composable
private fun rememberTimerRemaining(p: TimerPayload, intervalMs: Long): Long {
    val running = p.state.phase == TimerPhase.Running
    val tick by rememberTick(active = running, intervalMs = intervalMs)
    @Suppress("UNUSED_VARIABLE") val subscribe = tick
    return TimerMath.remainingMs(p.state, ClockSample(SystemClock.elapsedRealtime(), System.currentTimeMillis(), p.bootCount))
}

@Composable
internal fun TimerScene(
    m: SceneMetrics,
    p: TimerPayload,
    actions: OverlayActions,
    haptics: PulseHaptics,
    motion: MotionConfig,
) {
    TwoLayerScene(
        m,
        compact = { TimerCompact(p, motion) },
        expanded = { TimerExpanded(p, actions, haptics, motion) },
    )
}

@Composable
private fun CountdownText(remainingMs: Long, running: Boolean, style: TextStyle, motion: MotionConfig, modifier: Modifier = Modifier) {
    val seconds = (remainingMs + 999) / 1000
    val final = running && remainingMs in 1..FINAL_COUNTDOWN_MS
    val bump = remember { Animatable(1f) }
    LaunchedEffect(seconds, final) {
        if (final && !motion.reduced) {
            bump.snapTo(1.1f)
            bump.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
        }
    }
    PText(
        TimeFormat.clockCeil(remainingMs),
        style,
        modifier.graphicsLayer {
            scaleX = bump.value
            scaleY = bump.value
        },
        color = if (final) PulseColors.Warning else if (running) PulseColors.OnSurface else Muted,
    )
}

@Composable
private fun TimerCompact(p: TimerPayload, motion: MotionConfig) {
    val remaining = rememberTimerRemaining(p, 200L)
    val running = p.state.phase == TimerPhase.Running
    val final = running && remaining in 1..FINAL_COUNTDOWN_MS
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        PulsingIcon(PulseIcon.Timer, if (final) PulseColors.Warning else PulseColors.Blue, 18.dp, pulse = final, motion = motion)
        Spacer(Modifier.width(8.dp))
        CountdownText(remaining, running, PulseType.NumericCompact, motion)
    }
}

@Composable
private fun TimerExpanded(p: TimerPayload, actions: OverlayActions, haptics: PulseHaptics, motion: MotionConfig) {
    val remaining = rememberTimerRemaining(p, 200L)
    val running = p.state.phase == TimerPhase.Running
    val clock = ClockSample(SystemClock.elapsedRealtime(), System.currentTimeMillis(), p.bootCount)
    val final = running && remaining in 1..FINAL_COUNTDOWN_MS
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CountdownText(remaining, running, PulseType.Display, motion)
        SeekBar(
            progress = TimerMath.progress(p.state, clock),
            seekable = false,
            accent = if (final) PulseColors.Warning else PulseColors.Blue,
            onScrub = {},
            onCommit = {},
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PulseRoundButton(
                PulseIcon.Close, onClick = { haptics.tap(); actions.timer(TimerCommand.Cancel) },
                container = Color.White.copy(alpha = 0.12f),
            )
            PulseRoundButton(
                icon = if (running) PulseIcon.Pause else PulseIcon.Play,
                size = 58.dp, iconSize = 28.dp,
                container = PulseColors.Blue.copy(alpha = 0.9f),
                tint = Color(0xFF05060B),
                onClick = {
                    haptics.tap()
                    actions.timer(if (running) TimerCommand.Pause else TimerCommand.Resume)
                },
            )
            PulseTextButton("+1:00", onClick = { haptics.tap(); actions.timer(TimerCommand.Add(60_000L)) })
        }
    }
}

@Composable
internal fun TimerDoneScene(m: SceneMetrics, p: TimerDonePayload, actions: OverlayActions, haptics: PulseHaptics, motion: MotionConfig) {
    TwoLayerScene(
        m,
        compact = {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                PulsingIcon(PulseIcon.Bell, PulseColors.Warning, 18.dp, pulse = true, motion = motion)
                Spacer(Modifier.width(8.dp))
                PText("Timer done", PulseType.BodyCompact)
            }
        },
        expanded = {
            Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                PulsingIcon(PulseIcon.Bell, PulseColors.Warning, 34.dp, pulse = true, motion = motion)
                Spacer(Modifier.height(6.dp))
                PText("Time's up", PulseType.Title, textAlign = TextAlign.Center)
                PText("${TimeFormat.clockFloor(p.totalMs)} timer", PulseType.Body, color = Muted, textAlign = TextAlign.Center)
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulseTextButton("+1 min", onClick = { haptics.tap(); actions.timer(TimerCommand.Add(60_000L)) })
                    PulseTextButton("Stop", emphasized = true, accent = PulseColors.Warning, onClick = { haptics.tap(); actions.timer(TimerCommand.DismissAlarm) })
                }
            }
        },
    )
}

// ── Stopwatch ─────────────────────────────────────────────────────────────────────────────────

@Composable
private fun rememberStopwatchElapsed(p: StopwatchPayload, intervalMs: Long): Long {
    val running = p.state.phase == StopwatchPhase.Running
    val tick by rememberTick(active = running, intervalMs = intervalMs)
    @Suppress("UNUSED_VARIABLE") val subscribe = tick
    return StopwatchMath.elapsedMs(p.state, ClockSample(SystemClock.elapsedRealtime(), System.currentTimeMillis(), p.bootCount))
}

@Composable
internal fun StopwatchScene(m: SceneMetrics, p: StopwatchPayload, actions: OverlayActions, haptics: PulseHaptics) {
    TwoLayerScene(
        m,
        compact = { StopwatchCompact(p) },
        expanded = { StopwatchExpanded(p, actions, haptics) },
    )
}

@Composable
private fun StopwatchCompact(p: StopwatchPayload) {
    val elapsed = rememberStopwatchElapsed(p, 250L)
    val running = p.state.phase == StopwatchPhase.Running
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        PulseIconView(PulseIcon.Stopwatch, if (running) PulseColors.Violet else Muted, size = 18.dp)
        Spacer(Modifier.width(8.dp))
        PText(TimeFormat.clockFloor(elapsed), PulseType.NumericCompact, color = if (running) PulseColors.OnSurface else Muted)
    }
}

@Composable
private fun StopwatchExpanded(p: StopwatchPayload, actions: OverlayActions, haptics: PulseHaptics) {
    // Centiseconds need a fast tick, but only while this layer is actually on screen and running.
    val elapsed = rememberStopwatchElapsed(p, 50L)
    val running = p.state.phase == StopwatchPhase.Running
    val laps = remember(p.state.lapTotalsMs) { StopwatchMath.laps(p.state) }
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PText(
            TimeFormat.stopwatch(elapsed), PulseType.Display.copy(fontSize = 40.sp),
            color = if (running) PulseColors.OnSurface else Muted,
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PulseTextButton(
                if (running) "Lap" else "Reset",
                enabled = running || p.state.phase == StopwatchPhase.Paused,
                onClick = { haptics.tap(); actions.stopwatch(if (running) StopwatchCommand.Lap else StopwatchCommand.Reset) },
            )
            PulseRoundButton(
                icon = if (running) PulseIcon.Pause else PulseIcon.Play,
                size = 54.dp, iconSize = 26.dp,
                container = PulseColors.Violet.copy(alpha = 0.9f), tint = Color(0xFF05060B),
                onClick = { haptics.tap(); actions.stopwatch(if (running) StopwatchCommand.Pause else StopwatchCommand.Start) },
            )
            PulseRoundButton(
                PulseIcon.Close, container = Color.White.copy(alpha = 0.12f),
                onClick = { haptics.tap(); actions.stopwatch(StopwatchCommand.Reset) },
            )
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            laps.take(3).forEach { lap ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PText(
                        "Lap ${lap.number}", PulseType.Caption,
                        color = when {
                            lap.isFastest -> PulseColors.Charging
                            lap.isSlowest -> PulseColors.Warning
                            else -> Muted
                        },
                    )
                    PText(TimeFormat.stopwatch(lap.splitMs), PulseType.Caption.copy(fontFeatureSettings = "tnum"), color = Muted)
                    PText(TimeFormat.stopwatch(lap.totalMs), PulseType.Caption.copy(fontFeatureSettings = "tnum"), color = Faint)
                }
            }
        }
    }
}

// ── Bluetooth (top pill) ──────────────────────────────────────────────────────────────────────

internal fun deviceIcon(kind: DeviceKind): PulseIcon = when (kind) {
    DeviceKind.Earbuds -> PulseIcon.Earbuds
    DeviceKind.Headphones -> PulseIcon.Headphones
    DeviceKind.Speaker -> PulseIcon.Speaker
    DeviceKind.Watch -> PulseIcon.Watch
    DeviceKind.Car, DeviceKind.Other -> PulseIcon.Bluetooth
}

internal fun phaseLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.Discovered -> "Ready to pair"
    ConnectionPhase.Pairing -> "Pairing…"
    ConnectionPhase.Connecting -> "Connecting…"
    ConnectionPhase.Connected -> "Connected"
    ConnectionPhase.PairedNotConnected -> "Paired"
    ConnectionPhase.Failed -> "Couldn't connect"
}

/** "L 80%", "R 78%", "Case 92%", "Battery 55%" — only what the system reported. */
internal fun batteryParts(b: DeviceBattery): List<Pair<String, Int>> = buildList {
    b.left?.let { add("L" to it) }
    b.right?.let { add("R" to it) }
    b.case?.let { add("Case" to it) }
    b.single?.let { add("Battery" to it) }
}

@Composable
internal fun BluetoothScene(m: SceneMetrics, p: BluetoothPayload, actions: OverlayActions, haptics: PulseHaptics) {
    TwoLayerScene(
        m,
        compact = {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                PulseIconView(deviceIcon(p.kind), PulseColors.Blue, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                PText(p.name, PulseType.BodyCompact, Modifier.weight(1f))
                batteryParts(p.battery).firstOrNull()?.let { (_, pct) ->
                    Spacer(Modifier.width(8.dp))
                    PText("$pct%", PulseType.NumericCompact, color = Muted)
                }
            }
        },
        expanded = {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceIllustration(p.kind, Modifier.size(76.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    PText(p.name, PulseType.Title)
                    PText(phaseLabel(p.phase), PulseType.Body, color = if (p.phase == ConnectionPhase.Connected) PulseColors.Blue else Muted)
                    val parts = batteryParts(p.battery)
                    if (parts.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            parts.forEach { (label, pct) -> PText("$label $pct%", PulseType.Caption.copy(fontFeatureSettings = "tnum"), color = Muted) }
                        }
                    }
                }
            }
        },
    )
}
