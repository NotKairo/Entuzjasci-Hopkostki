package app.flux.timers

import kotlin.math.max

enum class StopwatchPhase { Idle, Running, Paused }

data class StopwatchState(
    val phase: StopwatchPhase = StopwatchPhase.Idle,
    /** Time banked from earlier Running stretches. */
    val accumulatedMs: Long = 0L,
    val startElapsedMs: Long = 0L,
    val startWallMs: Long = 0L,
    val bootCount: Int = -1,
    /** Cumulative elapsed time at each lap press, ascending. */
    val lapTotalsMs: List<Long> = emptyList(),
)

data class Lap(
    val number: Int,
    val splitMs: Long,
    val totalMs: Long,
    val isFastest: Boolean,
    val isSlowest: Boolean,
)

object StopwatchMath {

    fun elapsedMs(state: StopwatchState, clock: ClockSample): Long = when (state.phase) {
        StopwatchPhase.Idle -> 0L
        StopwatchPhase.Paused -> state.accumulatedMs
        StopwatchPhase.Running -> {
            val sameBoot = state.bootCount != -1 && state.bootCount == clock.bootCount
            val sinceStart = if (sameBoot) clock.elapsedRealtimeMs - state.startElapsedMs
            else clock.wallMs - state.startWallMs
            state.accumulatedMs + max(0L, sinceStart)
        }
    }

    fun start(state: StopwatchState, clock: ClockSample): StopwatchState {
        if (state.phase == StopwatchPhase.Running) return state
        return state.copy(
            phase = StopwatchPhase.Running,
            startElapsedMs = clock.elapsedRealtimeMs,
            startWallMs = clock.wallMs,
            bootCount = clock.bootCount,
        )
    }

    fun pause(state: StopwatchState, clock: ClockSample): StopwatchState {
        if (state.phase != StopwatchPhase.Running) return state
        return state.copy(phase = StopwatchPhase.Paused, accumulatedMs = elapsedMs(state, clock))
    }

    fun reset(): StopwatchState = StopwatchState()

    fun lap(state: StopwatchState, clock: ClockSample): StopwatchState {
        if (state.phase != StopwatchPhase.Running) return state
        val total = elapsedMs(state, clock)
        if (state.lapTotalsMs.lastOrNull()?.let { total <= it } == true) return state
        return state.copy(lapTotalsMs = state.lapTotalsMs + total)
    }

    /** Laps newest-first, with the fastest/slowest split flagged once there are at least two. */
    fun laps(state: StopwatchState): List<Lap> {
        val totals = state.lapTotalsMs
        if (totals.isEmpty()) return emptyList()
        val splits = totals.mapIndexed { i, t -> t - (if (i == 0) 0L else totals[i - 1]) }
        val fastest = if (splits.size >= 2) splits.min() else null
        val slowest = if (splits.size >= 2) splits.max() else null
        return totals.indices.reversed().map { i ->
            Lap(
                number = i + 1,
                splitMs = splits[i],
                totalMs = totals[i],
                isFastest = fastest != null && splits[i] == fastest && fastest != slowest,
                isSlowest = slowest != null && splits[i] == slowest && fastest != slowest,
            )
        }
    }
}
