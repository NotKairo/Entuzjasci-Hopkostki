package app.galaxypulse.media

import app.galaxypulse.engine.ActivityPayload
import kotlin.math.max
import kotlin.math.min

enum class PlaybackStatus { Playing, Paused, Buffering, Stopped }

/** What the active session actually lets us do. Controls the session doesn't support are hidden/disabled. */
data class TransportCaps(
    val canPlay: Boolean = false,
    val canPause: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val canSeek: Boolean = false,
) {
    val canTogglePlayback: Boolean get() = canPlay || canPause
}

/** A session-published custom action (typically shuffle / repeat). Icon lives in the media app's resources. */
data class MediaCustomAction(
    val actionId: String,
    val label: String,
    val iconResId: Int,
    val packageName: String,
)

/**
 * Immutable snapshot of the active media session. Position is stored the way the framework reports
 * it (value + the elapsed-realtime moment it was valid + speed); [positionAt] extrapolates between
 * updates so we never invent progress the session did not report.
 */
data class MediaSnapshot(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val artist: String,
    val album: String,
    val status: PlaybackStatus,
    val reportedPositionMs: Long,
    val reportedAtElapsedMs: Long,
    val speed: Float,
    val durationMs: Long,
    val caps: TransportCaps,
    /** Identity of the artwork in the artwork cache; null when the session provided none. */
    val artworkKey: String?,
    val customActions: List<MediaCustomAction> = emptyList(),
    val outputLabel: String? = null,
) : ActivityPayload {

    val isPlaying: Boolean get() = status == PlaybackStatus.Playing
    val hasDuration: Boolean get() = durationMs > 0L
    val hasPosition: Boolean get() = reportedPositionMs >= 0L

    /** Same track as [other]? Used to decide whether a snapshot is a new event (new revision). */
    fun sameTrackAs(other: MediaSnapshot): Boolean =
        packageName == other.packageName && title == other.title && artist == other.artist && album == other.album

    /** Current position, or null when the session never reported one. */
    fun positionAt(nowElapsedMs: Long): Long? {
        if (!hasPosition) return null
        val advanced = if (status == PlaybackStatus.Playing) {
            ((nowElapsedMs - reportedAtElapsedMs) * speed).toLong()
        } else {
            0L
        }
        val raw = reportedPositionMs + max(0L, advanced)
        return if (hasDuration) min(raw, durationMs) else raw
    }

    /** 0f..1f, or null when either duration or position is unknown (then no progress bar is drawn). */
    fun progressAt(nowElapsedMs: Long): Float? {
        if (!hasDuration) return null
        val p = positionAt(nowElapsedMs) ?: return null
        return (p.toFloat() / durationMs).coerceIn(0f, 1f)
    }
}
