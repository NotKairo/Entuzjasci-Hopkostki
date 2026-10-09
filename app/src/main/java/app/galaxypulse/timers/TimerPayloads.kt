package app.galaxypulse.timers

import app.galaxypulse.engine.ActivityPayload

/** The persisted state plus the boot count it must be evaluated against. */
data class TimerPayload(val state: TimerState, val bootCount: Int) : ActivityPayload

data class StopwatchPayload(val state: StopwatchState, val bootCount: Int) : ActivityPayload

/** Shown when a timer reaches zero; stays until dismissed. */
data class TimerDonePayload(val totalMs: Long) : ActivityPayload
