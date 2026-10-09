package app.flux.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSnapshotTest {

    private fun snapshot(
        status: PlaybackStatus = PlaybackStatus.Playing,
        position: Long = 10_000,
        reportedAt: Long = 1_000,
        speed: Float = 1f,
        duration: Long = 200_000,
        title: String = "Song",
    ) = MediaSnapshot(
        packageName = "com.example.player", appLabel = "Player", title = title, artist = "Artist", album = "Album",
        status = status, reportedPositionMs = position, reportedAtElapsedMs = reportedAt, speed = speed,
        durationMs = duration, caps = TransportCaps(canPlay = true, canPause = true), artworkKey = null,
    )

    @Test
    fun playingPositionAdvancesFromTheSessionsOwnTimestamp() {
        assertEquals(15_000L, snapshot().positionAt(nowElapsedMs = 6_000))
    }

    @Test
    fun playbackSpeedIsApplied() {
        assertEquals(20_000L, snapshot(speed = 2f).positionAt(nowElapsedMs = 6_000))
    }

    @Test
    fun pausedPositionDoesNotAdvance() {
        assertEquals(10_000L, snapshot(status = PlaybackStatus.Paused).positionAt(nowElapsedMs = 999_999))
    }

    @Test
    fun positionNeverExceedsTheDuration() {
        assertEquals(200_000L, snapshot(position = 199_000).positionAt(nowElapsedMs = 100_000))
    }

    @Test
    fun clockGoingBackwardsNeverRewindsTheBar() {
        assertEquals(10_000L, snapshot().positionAt(nowElapsedMs = 0))
    }

    @Test
    fun unknownPositionMeansNoProgressIsInvented() {
        val s = snapshot(position = -1)
        assertNull(s.positionAt(5_000))
        assertNull(s.progressAt(5_000))
    }

    @Test
    fun unknownDurationMeansNoProgressBar() {
        val s = snapshot(duration = 0)
        assertFalse(s.hasDuration)
        assertNull(s.progressAt(5_000))
        // The raw position is still available for a timestamp-only display.
        assertEquals(14_000L, s.positionAt(5_000))
    }

    @Test
    fun progressIsAFraction() {
        assertEquals(0.05f, snapshot(position = 10_000).progressAt(1_000)!!, 0.0001f)
    }

    @Test
    fun sameTrackIgnoresPlaybackStateAndPosition() {
        val a = snapshot()
        assertTrue(a.sameTrackAs(a.copy(status = PlaybackStatus.Paused, reportedPositionMs = 0)))
        assertFalse(a.sameTrackAs(a.copy(title = "Other")))
        assertFalse(a.sameTrackAs(a.copy(packageName = "x.y")))
    }

    @Test
    fun togglePlaybackNeedsPlayOrPause() {
        assertFalse(TransportCaps().canTogglePlayback)
        assertTrue(TransportCaps(canPause = true).canTogglePlayback)
    }
}
