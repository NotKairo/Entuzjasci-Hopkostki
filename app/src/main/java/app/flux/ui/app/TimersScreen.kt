package app.flux.ui.app

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.flux.AppGraph
import app.flux.timers.ClockSample
import app.flux.timers.StopwatchMath
import app.flux.timers.StopwatchPhase
import app.flux.timers.TimeFormat
import app.flux.timers.TimerMath
import app.flux.timers.TimerPhase
import app.flux.ui.anim.rememberTick
import app.flux.ui.design.FluxColors
import app.flux.ui.design.FluxType
import app.flux.ui.overlay.ProgressRing
import kotlinx.coroutines.launch

@Composable
fun TimersScreen(graph: AppGraph) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    ScreenColumn {
        ScreenTitle("Timers", "Kept as timestamps, so they keep running with the app closed, after a restart and across a reboot")
        ChoiceChips(listOf(0, 1), mode, { if (it == 0) "Timer" else "Stopwatch" }, { mode = it })
        if (mode == 0) TimerPanel(graph) else StopwatchPanel(graph)
    }
}

@Composable
private fun TimerPanel(graph: AppGraph) {
    val state by graph.timers.timer.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val boot = graph.timers.bootCount
    val running = state.phase == TimerPhase.Running
    val tick by rememberTick(active = running, intervalMs = 200L)
    @Suppress("UNUSED_VARIABLE") val subscribe = tick
    val clock = ClockSample(SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot)
    val remaining = TimerMath.remainingMs(state, clock)
    val final = running && remaining in 1..10_000

    SectionCard {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                ProgressRing(
                    progress = if (state.phase == TimerPhase.Idle) 0f else TimerMath.progress(state, clock),
                    color = if (final) FluxColors.Warning else FluxColors.Blue,
                    modifier = Modifier.size(240.dp), strokeWidth = 8.dp,
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.phase == TimerPhase.Finished) "Time's up" else TimeFormat.clockCeil(remaining),
                        style = FluxType.Display.copy(fontSize = 48.sp),
                        color = if (final) FluxColors.Warning else FluxColors.OnSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        when (state.phase) {
                            TimerPhase.Idle -> "Ready"
                            TimerPhase.Running -> "Running"
                            TimerPhase.Paused -> "Paused"
                            TimerPhase.Finished -> "Finished"
                        },
                        style = FluxType.Caption, color = FluxColors.OnSurfaceMuted,
                    )
                }
            }
        }

        when (state.phase) {
            TimerPhase.Idle -> {
                Text("Duration", style = FluxType.Caption, color = FluxColors.OnSurfaceMuted)
                ChoiceChips(
                    listOf(1, 3, 5, 10, 15, 30), (state.configuredMs / 60_000L).toInt().takeIf { state.configuredMs % 60_000L == 0L } ?: -1,
                    { "$it min" }, { minutes -> scope.launch { graph.timers.setTimerDuration(minutes * 60_000L) } },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("−1 min", { scope.launch { graph.timers.addTimerTime(-60_000L) } }, Modifier.weight(1f))
                    SecondaryButton("−10 s", { scope.launch { graph.timers.addTimerTime(-10_000L) } }, Modifier.weight(1f))
                    SecondaryButton("+10 s", { scope.launch { graph.timers.addTimerTime(10_000L) } }, Modifier.weight(1f))
                    SecondaryButton("+1 min", { scope.launch { graph.timers.addTimerTime(60_000L) } }, Modifier.weight(1f))
                }
                PrimaryButton("Start", { scope.launch { graph.timers.startTimer(state.configuredMs) } }, Modifier.fillMaxWidth())
            }
            TimerPhase.Running, TimerPhase.Paused -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Cancel", { scope.launch { graph.timers.cancelTimer() } }, Modifier.weight(1f))
                    SecondaryButton("+1 min", { scope.launch { graph.timers.addTimerTime(60_000L) } }, Modifier.weight(1f))
                    PrimaryButton(
                        if (running) "Pause" else "Resume",
                        { scope.launch { if (running) graph.timers.pauseTimer() else graph.timers.resumeTimer() } },
                        Modifier.weight(1f),
                    )
                }
            }
            TimerPhase.Finished -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Stop", { scope.launch { graph.notifier.cancelFinished(); graph.timers.dismissTimerAlarm() } }, Modifier.weight(1f))
                    PrimaryButton("+1 min", { scope.launch { graph.notifier.cancelFinished(); graph.timers.addTimerTime(60_000L) } }, Modifier.weight(1f))
                }
            }
        }
        Hint("A finished timer rings with a notification even if the overlay is off. Alarm accuracy: ${if (graph.timers.canScheduleExact()) "exact" else "approximate (allow Alarms & reminders on Setup)"}.")
    }
}

@Composable
private fun StopwatchPanel(graph: AppGraph) {
    val state by graph.timers.stopwatch.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val boot = graph.timers.bootCount
    val running = state.phase == StopwatchPhase.Running
    val tick by rememberTick(active = running, intervalMs = 50L)
    @Suppress("UNUSED_VARIABLE") val subscribe = tick
    val elapsed = StopwatchMath.elapsedMs(state, ClockSample(SystemClock.elapsedRealtime(), System.currentTimeMillis(), boot))
    val laps = StopwatchMath.laps(state)

    SectionCard {
        Text(
            TimeFormat.stopwatch(elapsed), style = FluxType.Display.copy(fontSize = 52.sp),
            color = if (running) FluxColors.OnSurface else FluxColors.OnSurfaceMuted,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                if (running) "Lap" else "Reset",
                { scope.launch { if (running) graph.timers.lapStopwatch() else graph.timers.resetStopwatch() } },
                Modifier.weight(1f), enabled = state.phase != StopwatchPhase.Idle,
            )
            PrimaryButton(
                if (running) "Pause" else if (state.phase == StopwatchPhase.Paused) "Resume" else "Start",
                { scope.launch { if (running) graph.timers.pauseStopwatch() else graph.timers.startStopwatch() } },
                Modifier.weight(1f),
            )
        }
    }

    if (laps.isNotEmpty()) {
        SectionCard("Laps") {
            laps.forEachIndexed { i, lap ->
                if (i > 0) HorizontalDivider(color = FluxColors.Stroke)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Lap ${lap.number}", style = FluxType.Body,
                        color = when {
                            lap.isFastest -> FluxColors.Charging
                            lap.isSlowest -> FluxColors.Warning
                            else -> FluxColors.OnSurface
                        },
                    )
                    Text(TimeFormat.stopwatch(lap.splitMs), style = FluxType.Numeric, color = FluxColors.OnSurface)
                    Text(TimeFormat.stopwatch(lap.totalMs), style = FluxType.Numeric, color = FluxColors.OnSurfaceMuted)
                }
            }
        }
    }
}
