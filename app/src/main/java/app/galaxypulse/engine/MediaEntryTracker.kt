package app.galaxypulse.engine

import app.galaxypulse.media.MediaSnapshot
import app.galaxypulse.media.PlaybackStatus

/**
 * Decides, for each media snapshot, (a) whether it is a *genuinely new event* (a new revision, which
 * is what lets a dismissed pill come back) and (b) when a paused session should auto-hide.
 *
 * New revision when: nothing was playing before, the track or app changed, or playback resumed.
 * Not a new revision: position/metadata refreshes, artwork arriving late, repeated state callbacks.
 *
 * The auto-hide deadline is fixed at the moment playback stops and is *not* extended by later
 * refreshes of the same paused state — repeated system callbacks can't keep a stale pill alive.
 */
class MediaEntryTracker(private val nextRevision: () -> Long) {
    private var last: MediaSnapshot? = null
    private var revision = 0L
    private var inactiveSince: Long? = null

    data class Decision(val revision: Long, val expiresAt: Long?)

    fun update(snapshot: MediaSnapshot, now: Long, hideDelayMs: Long): Decision {
        val previous = last
        val newTrack = previous == null || !previous.sameTrackAs(snapshot)
        val resumed = previous != null && !previous.isActiveStatus() && snapshot.isActiveStatus()
        if (newTrack || resumed) revision = nextRevision()
        last = snapshot

        if (snapshot.isActiveStatus()) {
            inactiveSince = null
            return Decision(revision, expiresAt = null)
        }
        val since = inactiveSince ?: now.also { inactiveSince = it }
        return Decision(revision, expiresAt = since + hideDelayMs)
    }

    fun reset() {
        last = null
        inactiveSince = null
    }

    private fun MediaSnapshot.isActiveStatus() = status == PlaybackStatus.Playing || status == PlaybackStatus.Buffering
}
