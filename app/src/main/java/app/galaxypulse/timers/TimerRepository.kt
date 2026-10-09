package app.galaxypulse.timers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.galaxypulse.core.ClockSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.timerStore: DataStore<Preferences> by preferencesDataStore(name = "pulse_timers")

/**
 * Timer and stopwatch state, persisted as timestamps (never as a ticking counter) so they survive
 * the UI closing, process death and reboots. Remaining/elapsed time is *derived* from the clocks at
 * read time by [TimerMath] / [StopwatchMath].
 *
 * Completion is scheduled with an exact alarm, so a timer finishes on time with the app closed.
 */
class TimerRepository(context: Context, scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val store = appContext.timerStore
    private val timerKey = stringPreferencesKey("timer")
    private val stopwatchKey = stringPreferencesKey("stopwatch")

    val timer: StateFlow<TimerState> = store.data
        .map { StateCodec.decodeTimer(it[timerKey]) }
        .stateIn(scope, SharingStarted.Eagerly, TimerState())

    val stopwatch: StateFlow<StopwatchState> = store.data
        .map { StateCodec.decodeStopwatch(it[stopwatchKey]) }
        .stateIn(scope, SharingStarted.Eagerly, StopwatchState())

    /** Called (on whichever thread settles it) the first time a timer is found finished. */
    var onTimerFinished: ((TimerState) -> Unit)? = null

    val bootCount: Int get() = ClockSource.bootCount(appContext)

    // ── Timer ─────────────────────────────────────────────────────────────────────────────────

    suspend fun startTimer(durationMs: Long) = mutateTimer { s, c -> TimerMath.start(s, durationMs, c) }
    suspend fun pauseTimer() = mutateTimer { s, c -> TimerMath.pause(s, c) }
    suspend fun resumeTimer() = mutateTimer { s, c -> TimerMath.resume(s, c) }
    suspend fun cancelTimer() = mutateTimer { s, _ -> TimerMath.cancel(s) }
    suspend fun addTimerTime(extraMs: Long) = mutateTimer { s, c -> TimerMath.addTime(s, extraMs, c) }
    suspend fun dismissTimerAlarm() = mutateTimer { s, _ -> TimerMath.cancel(s) }

    /** Sets the duration the next start will use (only while no timer is active). */
    suspend fun setTimerDuration(durationMs: Long) = mutateTimer { s, _ ->
        if (s.phase == TimerPhase.Idle) s.copy(configuredMs = durationMs.coerceIn(1_000L, MAX_DURATION_MS)) else s
    }

    /**
     * Brings the persisted timer up to date (re-anchors after a reboot, flips to Finished when due),
     * re-arms the alarm and fires [onTimerFinished] once. Safe to call from anywhere, any number of
     * times: the alarm receiver, the boot receiver and the overlay service all do.
     */
    suspend fun settle(): TimerState = mutateTimer { s, _ -> s }

    private suspend fun mutateTimer(change: (TimerState, ClockSample) -> TimerState): TimerState {
        var result = TimerState()
        var justFinished = false
        store.edit { prefs ->
            val clock = ClockSource.sample(appContext)
            val stored = StateCodec.decodeTimer(prefs[timerKey])
            val after = TimerMath.settle(change(TimerMath.settle(stored, clock), clock), clock)
            // Report "finished" exactly once: on the write that moves the stored state into Finished.
            justFinished = stored.phase != TimerPhase.Finished && after.phase == TimerPhase.Finished
            prefs[timerKey] = StateCodec.encode(after)
            result = after
        }
        schedule(result)
        if (justFinished) onTimerFinished?.invoke(result)
        return result
    }

    // ── Stopwatch ─────────────────────────────────────────────────────────────────────────────

    suspend fun startStopwatch() = mutateStopwatch { s, c -> StopwatchMath.start(s, c) }
    suspend fun pauseStopwatch() = mutateStopwatch { s, c -> StopwatchMath.pause(s, c) }
    suspend fun lapStopwatch() = mutateStopwatch { s, c -> StopwatchMath.lap(s, c) }
    suspend fun resetStopwatch() = mutateStopwatch { _, _ -> StopwatchMath.reset() }

    private suspend fun mutateStopwatch(change: (StopwatchState, ClockSample) -> StopwatchState) {
        store.edit { prefs ->
            val clock = ClockSource.sample(appContext)
            val current = StateCodec.decodeStopwatch(prefs[stopwatchKey])
            prefs[stopwatchKey] = StateCodec.encode(change(current, clock))
        }
    }

    // ── Alarm ─────────────────────────────────────────────────────────────────────────────────

    /** Whether the completion alarm can be exact. Diagnostics shows this; if not, it degrades to inexact. */
    fun canScheduleExact(): Boolean = appContext.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun schedule(state: TimerState) {
        val alarms = appContext.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            appContext, REQUEST_CODE,
            Intent(appContext, TimerAlarmReceiver::class.java).setAction(TimerAlarmReceiver.ACTION_FINISHED),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val at = TimerMath.alarmAtElapsedMs(state, ClockSource.sample(appContext))
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending)
        }
    }

    private companion object {
        const val REQUEST_CODE = 7001
        const val MAX_DURATION_MS = 99L * 3_600_000L
    }
}
