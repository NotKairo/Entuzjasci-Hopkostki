package app.galaxypulse.media

import app.galaxypulse.media.MediaSessionPicker.Candidate
import app.galaxypulse.media.PlaybackStatus.Buffering
import app.galaxypulse.media.PlaybackStatus.Paused
import app.galaxypulse.media.PlaybackStatus.Playing
import app.galaxypulse.media.PlaybackStatus.Stopped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionPickerTest {

    @Test
    fun noSessionsMeansNothing() {
        assertNull(MediaSessionPicker.pick(emptyList(), null))
    }

    @Test
    fun aPlayingSessionBeatsEverythingElse() {
        val list = listOf(Candidate("a", Paused), Candidate("b", Playing), Candidate("c", Playing))
        assertEquals("b", MediaSessionPicker.pick(list, currentId = "a"))
    }

    @Test
    fun bufferingCountsAsPlaying() {
        assertEquals("b", MediaSessionPicker.pick(listOf(Candidate("a", Paused), Candidate("b", Buffering)), null))
    }

    @Test
    fun pausingKeepsFollowingTheSameSessionInsteadOfJumping() {
        val list = listOf(Candidate("a", Stopped), Candidate("b", Paused))
        assertEquals("b", MediaSessionPicker.pick(list, currentId = "b"))
        assertEquals("b", MediaSessionPicker.pick(list, currentId = null)) // paused beats stopped
    }

    @Test
    fun currentSessionThatDisappearedFallsBackToTheFirstSensibleOne() {
        val list = listOf(Candidate("a", Stopped), Candidate("c", Paused))
        assertEquals("c", MediaSessionPicker.pick(list, currentId = "gone"))
    }

    @Test
    fun ignoredAppsAreNeverChosen() {
        val list = listOf(Candidate("a", Playing, ignored = true), Candidate("b", Paused))
        assertEquals("b", MediaSessionPicker.pick(list, null))
        assertNull(MediaSessionPicker.pick(listOf(Candidate("a", Playing, ignored = true)), null))
    }

    @Test
    fun whenNothingElseApplies_theFirstInSystemOrderWins() {
        val list = listOf(Candidate("a", Stopped), Candidate("b", Stopped))
        assertEquals("a", MediaSessionPicker.pick(list, null))
    }

    @Test
    fun stableAcrossRepeatedPicks() {
        val list = listOf(Candidate("a", Paused), Candidate("b", Paused))
        var current: String? = null
        repeat(5) { current = MediaSessionPicker.pick(list, current) }
        assertEquals("a", current)
    }
}
