package app.flux.notifications

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.flux.FluxApp

/**
 * Bound by Android once the user grants notification access. It feeds two things: notifications
 * (via [NotificationRepository]) and media-session discovery (MediaSessionManager only reveals other
 * apps' sessions to a component that holds this access).
 */
class FluxNotificationListener : NotificationListenerService() {

    private val graph get() = (applicationContext as FluxApp).graph

    override fun onListenerConnected() {
        graph.log("notification access connected")
        graph.media.attach(ComponentName(this, FluxNotificationListener::class.java))
    }

    override fun onListenerDisconnected() {
        graph.log("notification access disconnected")
        graph.media.detach()
        // Ask Android to bind us again (it may have unbound us under memory pressure).
        requestRebind(ComponentName(this, FluxNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        graph.notifications.onPosted(sbn, rankingMap)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        graph.notifications.onPosted(sbn, currentRanking)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        graph.notifications.onRemoved(sbn)
    }
}
