package app.galaxypulse.timers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.galaxypulse.PulseApp
import kotlinx.coroutines.launch

/**
 * Wakes the app when a timer is due (exact alarm) and handles the two buttons on the "Timer finished"
 * notification. The work happens off the main thread via `goAsync()`, and everything it calls is
 * idempotent, so a duplicate delivery is harmless.
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val graph = (context.applicationContext as PulseApp).graph
        val pending = goAsync()
        graph.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_FINISHED -> {
                        graph.log("timer alarm fired")
                        graph.timers.settle()
                        graph.ensureOverlayRunningIfEnabled()
                    }
                    ACTION_ADD_MINUTE -> {
                        graph.notifier.cancelFinished()
                        graph.timers.addTimerTime(60_000L)
                    }
                    ACTION_STOP -> {
                        graph.notifier.cancelFinished()
                        graph.timers.dismissTimerAlarm()
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FINISHED = "app.galaxypulse.action.TIMER_FINISHED"
        const val ACTION_ADD_MINUTE = "app.galaxypulse.action.TIMER_ADD_MINUTE"
        const val ACTION_STOP = "app.galaxypulse.action.TIMER_STOP"
    }
}
