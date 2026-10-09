package app.galaxypulse.notifications

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.galaxypulse.engine.EventDeduper
import app.galaxypulse.settings.PulseSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Turns statuses posted to the notification listener into overlay events, applying the user's rules
 * and Android's privacy signals. Nothing here cancels, snoozes or alters another app's notification,
 * and message text is withheld whenever privacy rules say so (see [NotificationPrivacy]).
 */
class NotificationRepository(
    context: Context,
    private val settings: () -> PulseSettings,
    private val nextRevision: () -> Long,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    sealed interface Event {
        data class Posted(val payload: NotificationPayload, val revision: Long) : Event
        data class Removed(val sbnKey: String) : Event
    }

    private val appContext = context.applicationContext
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events

    /** The real intents stay here; the overlay only ever holds indexes. */
    private class Pending(val contentIntent: PendingIntent?, val actions: List<PendingIntent>)

    private val pending = LinkedHashMap<String, Pending>()
    private val labels = HashMap<String, String>()
    private val deduper = EventDeduper()

    fun onPosted(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?) {
        val s = settings()
        val n = sbn.notification ?: return
        if (sbn.packageName == appContext.packageName) return

        val call = isIncomingCall(n)
        if (!call) {
            if (!s.enableNotifications) return
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            // Music is presented by the media scene; ongoing/foreground-service/progress items are not "events".
            if (n.category == Notification.CATEGORY_TRANSPORT) return
            if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return
            val extras = n.extras
            if (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return
        }
        if (sbn.packageName in s.blockedNotificationApps) return

        // Respect the system's own ranking: importance, and Do Not Disturb.
        val ranking = NotificationListenerService.Ranking()
        if (rankingMap?.getRanking(sbn.key, ranking) == true) {
            if (!call && ranking.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT && !s.includeSilentNotifications) return
            if (!ranking.matchesInterruptionFilter()) return
        }

        val locked = keyguard?.isKeyguardLocked == true
        val visibility = NotificationPrivacy.decide(
            mode = s.notificationContent,
            deviceLocked = locked,
            notificationVisibility = n.visibility,
            appHidesContent = sbn.packageName in s.hideContentApps,
        )
        if (visibility == ContentVisibility.Suppress) return
        val showContent = visibility == ContentVisibility.ShowAll

        val extras = n.extras
        val title = if (showContent) NotificationPrivacy.clip(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(), 80) else null
        val text = if (showContent) {
            NotificationPrivacy.clip(
                (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString(),
                140,
            )
        } else {
            null
        }

        // Inline-reply actions need the app's own UI; only plain tap actions are offered.
        val usable = n.actions.orEmpty()
            .filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() && !it.title.isNullOrBlank() }
            .take(2)
        val payload = NotificationPayload(
            sbnKey = sbn.key,
            packageName = sbn.packageName,
            appLabel = labelFor(sbn.packageName),
            title = title,
            text = text,
            contentHidden = !showContent,
            actions = usable.mapIndexed { i, a -> NotificationActionLabel(i, a.title.toString()) },
            hasContentIntent = n.contentIntent != null,
            isIncomingCall = call,
        )

        // Apps re-post identical notifications constantly (progress, sync, typing). Show each once.
        if (!deduper.accept(sbn.key, payload.hashCode(), now(), DEDUPE_WINDOW_MS)) return

        pending[sbn.key] = Pending(n.contentIntent, usable.map { it.actionIntent })
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
        log("notification: ${payload.appLabel}${if (call) " (call)" else ""}${if (payload.contentHidden) " [content hidden]" else ""}")
        _events.tryEmit(Event.Posted(payload, nextRevision()))
    }

    fun onRemoved(sbn: StatusBarNotification) {
        pending.remove(sbn.key)
        _events.tryEmit(Event.Removed(sbn.key))
    }

    fun performAction(sbnKey: String, index: Int) {
        val intent = pending[sbnKey]?.actions?.getOrNull(index) ?: return
        send(intent)
    }

    fun open(sbnKey: String) {
        send(pending[sbnKey]?.contentIntent ?: return)
    }

    private fun send(intent: PendingIntent) {
        try {
            intent.send()
        } catch (_: PendingIntent.CanceledException) {
            // The posting app already withdrew it; nothing to do.
        }
    }

    private fun isIncomingCall(n: Notification): Boolean {
        if (n.category != Notification.CATEGORY_CALL) return false
        val extras = n.extras
        if (extras.getString(Notification.EXTRA_TEMPLATE) == "android.app.Notification\$CallStyle") {
            return extras.getInt(Notification.EXTRA_CALL_TYPE, -1) == Notification.CallStyle.CALL_TYPE_INCOMING
        }
        // Older dialers announce an incoming call with a full-screen intent.
        return n.fullScreenIntent != null
    }

    private fun labelFor(packageName: String): String = labels.getOrPut(packageName) {
        try {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private companion object {
        const val DEDUPE_WINDOW_MS = 10_000L
        const val MAX_PENDING = 40
    }
}
