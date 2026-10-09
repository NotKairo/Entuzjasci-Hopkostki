package app.galaxypulse.timers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.provider.Settings
import app.galaxypulse.MainActivity
import app.galaxypulse.R

/**
 * The audible, system-level half of a finished timer. The overlay shows a "Timer done" card, but sound
 * and vibration must work even when the overlay is disabled or the screen is off, so they come from a
 * real alarm-category notification (its sound repeats until the notification is dismissed or times out).
 */
class TimerNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Timer alarms", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Rings when a timer finishes"
            setSound(
                Settings.System.DEFAULT_ALARM_ALERT_URI,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 250, 400, 250, 400)
        }
        manager.createNotificationChannel(channel)
    }

    fun showFinished(totalMs: Long) {
        ensureChannel()
        val open = PendingIntent.getActivity(
            appContext, 0, Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(code: Int, label: String, action: String) = Notification.Action.Builder(
            Icon.createWithResource(appContext, R.drawable.ic_stat_pulse), label, broadcast(code, action),
        ).build()

        val notification = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pulse)
            .setContentTitle("Timer finished")
            .setContentText("${TimeFormat.clockFloor(totalMs)} timer is done")
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(RING_TIMEOUT_MS)
            .setDeleteIntent(broadcast(2, TimerAlarmReceiver.ACTION_STOP))
            .addAction(action(1, "+1 min", TimerAlarmReceiver.ACTION_ADD_MINUTE))
            .addAction(action(2, "Stop", TimerAlarmReceiver.ACTION_STOP))
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT } // keep ringing until acted on
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancelFinished() = manager.cancel(NOTIFICATION_ID)

    private fun broadcast(code: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        appContext, code, Intent(appContext, TimerAlarmReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val CHANNEL_ID = "timer_alarm"
        const val NOTIFICATION_ID = 4101
        const val RING_TIMEOUT_MS = 60_000L
    }
}
