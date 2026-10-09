package app.flux.notifications

import app.flux.engine.ActivityPayload
import app.flux.settings.NotificationContentMode

data class NotificationActionLabel(val index: Int, val label: String)

/**
 * A notification as the overlay presents it. The real [android.app.PendingIntent]s stay in
 * [NotificationRepository]; the overlay only gets labels and indexes, so payloads stay cheap to compare.
 */
data class NotificationPayload(
    val sbnKey: String,
    val packageName: String,
    val appLabel: String,
    val title: String?,
    val text: String?,
    /** True when content was withheld (privacy); only [appLabel] may be shown. */
    val contentHidden: Boolean,
    val actions: List<NotificationActionLabel>,
    val hasContentIntent: Boolean,
    val isIncomingCall: Boolean,
) : ActivityPayload

/** What we may reveal about one notification. */
enum class ContentVisibility { ShowAll, ShowAppOnly, Suppress }

/**
 * Privacy rules, pure so they can be tested exhaustively. Constants mirror
 * `android.app.Notification.VISIBILITY_*`.
 *
 * - SECRET notifications are never shown while the device is locked.
 * - We never reveal more than the notification itself allows on a lock screen: with the device
 *   locked, only a PUBLIC notification may show content, and only in [NotificationContentMode.Full].
 */
object NotificationPrivacy {
    const val VISIBILITY_SECRET = -1
    const val VISIBILITY_PRIVATE = 0
    const val VISIBILITY_PUBLIC = 1

    fun decide(
        mode: NotificationContentMode,
        deviceLocked: Boolean,
        notificationVisibility: Int,
        appHidesContent: Boolean,
    ): ContentVisibility {
        if (deviceLocked && notificationVisibility == VISIBILITY_SECRET) return ContentVisibility.Suppress
        if (appHidesContent || mode == NotificationContentMode.AppOnly) return ContentVisibility.ShowAppOnly
        if (deviceLocked) {
            return if (mode == NotificationContentMode.Full && notificationVisibility == VISIBILITY_PUBLIC) {
                ContentVisibility.ShowAll
            } else {
                ContentVisibility.ShowAppOnly
            }
        }
        return ContentVisibility.ShowAll
    }

    /** Text previews are trimmed so one chatty message can't blow up the card. */
    fun clip(text: String?, max: Int): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length <= max) t else t.take(max - 1).trimEnd() + "…"
    }
}
