package app.galaxypulse.timers

import kotlin.math.max

/**
 * Time sources sampled at one instant. Elapsed time is computed from persisted timestamps against
 * these, never from a running animation or a counter that only ticks while the UI is visible.
 *
 * [elapsedRealtimeMs] is monotonic within one boot (immune to wall-clock changes) but resets on
 * reboot; [bootCount] (Settings.Global.BOOT_COUNT) tells us when that happened, in which case the
 * wall clock is the fallback.
 */
data class ClockSample(
    val elapsedRealtimeMs: Long,
    val wallMs: Long,
    val bootCount: Int,
)

enum class TimerPhase { Idle, Running, Paused, Finished }

data class TimerState(
    val phase: TimerPhase = TimerPhase.Idle,
    /** The duration the user dialled; what a fresh start uses. */
    val configuredMs: Long = DEFAULT_DURATION_MS,
    /** Length of the current run including added time (for progress). */
    val totalMs: Long = 0L,
    /** Remaining time while Paused. */
    val pausedRemainingMs: Long = 0L,
    val endElapsedMs: Long = 0L,
    val endWallMs: Long = 0L,
    val bootCount: Int = -1,
    val finishedAtWallMs: Long = 0L,
) {
    companion object {
        const val DEFAULT_DURATION_MS = 5 * 60_000L
    }
}

object TimerMath {

    fun remainingMs(state: TimerState, clock: ClockSample): Long = when (state.phase) {
        TimerPhase.Idle -> state.configuredMs
        TimerPhase.Paused -> state.pausedRemainingMs
        TimerPhase.Finished -> 0L
        TimerPhase.Running -> {
            val sameBoot = state.bootCount != -1 && state.bootCount == clock.bootCount
            max(0L, if (sameBoot) state.endElapsedMs - clock.elapsedRealtimeMs else state.endWallMs - clock.wallMs)
        }
    }

    /** 0f at the start of a run, 1f when it has elapsed. 0f when not running/paused. */
    fun progress(state: TimerState, clock: ClockSample): Float {
        if (state.phase == TimerPhase.Finished) return 1f
        if (state.totalMs <= 0L || state.phase == TimerPhase.Idle) return 0f
        val remaining = remainingMs(state, clock)
        return (1f - remaining.toFloat() / state.totalMs).coerceIn(0f, 1f)
    }

    fun start(state: TimerState, durationMs: Long, clock: ClockSample): TimerState {
        val d = max(1_000L, durationMs)
        return state.copy(
            phase = TimerPhase.Running,
            configuredMs = d,
            totalMs = d,
            pausedRemainingMs = 0L,
            endElapsedMs = clock.elapsedRealtimeMs + d,
            endWallMs = clock.wallMs + d,
            bootCount = clock.bootCount,
            finishedAtWallMs = 0L,
        )
    }

    fun pause(state: TimerState, clock: ClockSample): TimerState {
        if (state.phase != TimerPhase.Running) return state
        return state.copy(phase = TimerPhase.Paused, pausedRemainingMs = remainingMs(state, clock))
    }

    fun resume(state: TimerState, clock: ClockSample): TimerState {
        if (state.phase != TimerPhase.Paused) return state
        val remaining = max(1_000L, state.pausedRemainingMs)
        return state.copy(
            phase = TimerPhase.Running,
            endElapsedMs = clock.elapsedRealtimeMs + remaining,
            endWallMs = clock.wallMs + remaining,
            bootCount = clock.bootCount,
        )
    }

    fun cancel(state: TimerState): TimerState =
        TimerState(configuredMs = state.configuredMs)

    /** Adds time. On a Finished timer this starts a new run of [extraMs] ("+1 min" after the alarm). */
    fun addTime(state: TimerState, extraMs: Long, clock: ClockSample): TimerState = when (state.phase) {
        TimerPhase.Running -> state.copy(
            totalMs = state.totalMs + extraMs,
            endElapsedMs = state.endElapsedMs + extraMs,
            endWallMs = state.endWallMs + extraMs,
        )
        TimerPhase.Paused -> state.copy(
            totalMs = state.totalMs + extraMs,
            pausedRemainingMs = state.pausedRemainingMs + extraMs,
        )
        TimerPhase.Finished -> start(state, extraMs, clock)
        TimerPhase.Idle -> state.copy(configuredMs = max(1_000L, state.configuredMs + extraMs))
    }

    /**
     * Brings a persisted state up to date: re-anchors a running timer after a reboot and flips it to
     * Finished when its time is up. Idempotent, so the alarm receiver, the service and the UI may all
     * call it.
     */
    fun settle(state: TimerState, clock: ClockSample): TimerState {
        if (state.phase != TimerPhase.Running) return state
        val remaining = remainingMs(state, clock)
        if (remaining <= 0L) {
            return state.copy(
                phase = TimerPhase.Finished,
                pausedRemainingMs = 0L,
                finishedAtWallMs = if (state.finishedAtWallMs > 0L) state.finishedAtWallMs else clock.wallMs,
            )
        }
        if (state.bootCount != clock.bootCount) {
            return state.copy(
                endElapsedMs = clock.elapsedRealtimeMs + remaining,
                endWallMs = clock.wallMs + remaining,
                bootCount = clock.bootCount,
            )
        }
        return state
    }

    /** Monotonic time at which the completion alarm should fire, or null when nothing is running. */
    fun alarmAtElapsedMs(state: TimerState, clock: ClockSample): Long? =
        if (state.phase == TimerPhase.Running) clock.elapsedRealtimeMs + remainingMs(state, clock) else null
}
