package app.flux.engine

import app.flux.media.MediaSnapshot
import app.flux.media.PlaybackStatus
import app.flux.media.TransportCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaEntryTrackerTest {

    private var counter = 0L
    private val tracker = MediaEntryTracker { ++counter }

    private fun snap(
        status: PlaybackStatus = PlaybackStatus.Playing,
        title: String = "Song A",
        position: Long = 0,
        pkg: String = "com.spotify",
    ) = MediaSnapshot(
        packageName = pkg, appLabel = "App", title = title, artist = "Artist", album = "Album",
        status = status, reportedPositionMs = position, reportedAtElapsedMs = 0, speed = 1f, durationMs = 200_000,
        caps = TransportCaps(), artworkKey = null,
    )

    private val delay = 8_000L

    @Test
    fun firstSnapshotIsANewEventWithNoExpiryWhilePlaying() {
        val d = tracker.update(snap(), now = 100, hideDelayMs = delay)
        assertEquals(1L, d.revision)
        assertNull(d.expiresAt)
    }

    @Test
    fun positionRefreshesKeepTheSameRevision() {
        val first = tracker.update(snap(position = 0), 100, delay)
        val second = tracker.update(snap(position = 5_000), 200, delay)
        assertEquals(first.revision, second.revision)
    }

    @Test
    fun aNewTrackIsANewEvent() {
        val a = tracker.update(snap(title = "A"), 100, delay)
        val b = tracker.update(snap(title = "B"), 200, delay)
        assertNotEquals(a.revision, b.revision)
    }

    @Test
    fun aDifferentAppIsANewEvent() {
        val a = tracker.update(snap(pkg = "x.one"), 100, delay)
        val b = tracker.update(snap(pkg = "x.two"), 200, delay)
        assertNotEquals(a.revision, b.revision)
    }

    @Test
    fun pausingStartsTheHideCountdownAndLaterRefreshesDoNotExtendIt() {
        tracker.update(snap(), 0, delay)
        val paused = tracker.update(snap(PlaybackStatus.Paused), 1_000, delay)
        assertEquals(9_000L, paused.expiresAt)
        val refreshed = tracker.update(snap(PlaybackStatus.Paused, position = 10), 5_000, delay)
        assertEquals("deadline must not slide", 9_000L, refreshed.expiresAt)
        assertEquals(paused.revision, refreshed.revision)
    }

    @Test
    fun resumingPlaybackIsANewEventAndClearsTheDeadline() {
        tracker.update(snap(), 0, delay)
        val paused = tracker.update(snap(PlaybackStatus.Paused), 1_000, delay)
        val resumed = tracker.update(snap(PlaybackStatus.Playing), 2_000, delay)
        assertNotEquals(paused.revision, resumed.revision)
        assertNull(resumed.expiresAt)
        // A second pause gets a fresh countdown.
        val pausedAgain = tracker.update(snap(PlaybackStatus.Paused), 20_000, delay)
        assertEquals(28_000L, pausedAgain.expiresAt)
    }

    @Test
    fun stoppedBehavesLikePaused() {
        tracker.update(snap(), 0, delay)
        assertEquals(10_000L, tracker.update(snap(PlaybackStatus.Stopped), 2_000, delay).expiresAt)
    }

    @Test
    fun bufferingCountsAsActive() {
        tracker.update(snap(), 0, delay)
        val d = tracker.update(snap(PlaybackStatus.Buffering), 100, delay)
        assertNull(d.expiresAt)
    }

    @Test
    fun resetForgetsHistory() {
        val a = tracker.update(snap(), 0, delay)
        tracker.reset()
        val b = tracker.update(snap(), 10, delay)
        assertNotEquals(a.revision, b.revision)
    }
}
