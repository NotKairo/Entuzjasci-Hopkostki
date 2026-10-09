package app.galaxypulse.engine

import app.galaxypulse.engine.SchedulerEvent.Dismiss
import app.galaxypulse.engine.SchedulerEvent.Post
import app.galaxypulse.engine.SchedulerEvent.Remove
import app.galaxypulse.engine.SchedulerEvent.SetExpanded
import app.galaxypulse.engine.SchedulerEvent.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivitySchedulerTest {

    private object P : ActivityPayload
    private val priorities = PriorityConfig()

    private fun persistent(
        key: String,
        kind: ActivityKind,
        revision: Long = 1,
        at: Long = 0,
        expiresAt: Long? = null,
    ) = ActivityEntry(
        key = key, kind = kind, payload = P, rank = priorities.rankOf(kind),
        revision = revision, postedAt = at, expiresAt = expiresAt,
    )

    private fun transient(
        key: String,
        kind: ActivityKind,
        revision: Long = 1,
        at: Long = 0,
        displayMs: Long = 4_000,
        staleAt: Long? = null,
    ) = ActivityEntry(
        key = key, kind = kind, payload = P, rank = priorities.rankOf(kind),
        revision = revision, postedAt = at, displayMs = displayMs, staleAt = staleAt,
    )

    private fun SchedulerState.after(event: SchedulerEvent, now: Long) =
        ActivityScheduler.reduce(this, event, now)

    private val empty = SchedulerState()

    @Test
    fun emptyStateIsHidden() {
        assertNull(empty.after(Tick, 0).front)
    }

    @Test
    fun mediaShowsAndHidesWhenRemoved() {
        val s = empty.after(Post(persistent("media", ActivityKind.Media)), 0)
        assertEquals("media", s.frontKey)
        assertNull(s.after(Remove("media"), 10).front)
    }

    @Test
    fun notificationInterruptsMediaThenMediaReturns() {
        var s = empty.after(Post(persistent("media", ActivityKind.Media)), 0)
        s = s.after(Post(transient("n1", ActivityKind.Notification, at = 100, displayMs = 5_000)), 100)
        assertEquals("n1", s.frontKey)
        // Still showing just before it ends.
        assertEquals("n1", s.after(Tick, 5_099).frontKey)
        // Gone at displayMs after it first reached the front; media is back without being re-posted.
        val back = s.after(Tick, 5_100)
        assertEquals("media", back.frontKey)
        assertFalse("n1" in back.entries)
    }

    @Test
    fun runningTimerIsNotInterruptedByNotificationOrCharging() {
        var s = empty.after(Post(persistent("timer", ActivityKind.Timer)), 0)
        s = s.after(Post(transient("n1", ActivityKind.Notification, staleAt = 10_000)), 10)
        s = s.after(Post(transient("chg", ActivityKind.Charging, staleAt = 10_000)), 20)
        assertEquals("timer", s.frontKey)
        // Waiting transients never started their display clock.
        assertNull(s.entries.getValue("n1").shownAt)
        // ...and are dropped once stale, without ever showing.
        val later = s.after(Tick, 10_000)
        assertEquals("timer", later.frontKey)
        assertFalse("n1" in later.entries)
        assertFalse("chg" in later.entries)
    }

    @Test
    fun timerDoneOutranksRunningTimer() {
        var s = empty.after(Post(persistent("timer", ActivityKind.Timer)), 0)
        s = s.after(Post(persistent("timer-done", ActivityKind.TimerDone, at = 50)), 50)
        assertEquals("timer-done", s.frontKey)
        // Dismissing the alert returns to the timer entry, which was never lost.
        s = s.after(Dismiss("timer-done"), 60)
        assertEquals("timer", s.frontKey)
    }

    @Test
    fun bluetoothInterruptsMediaButNotTimer() {
        val media = empty.after(Post(persistent("media", ActivityKind.Media)), 0)
            .after(Post(transient("bt:1", ActivityKind.Bluetooth, at = 5)), 5)
        assertEquals("bt:1", media.frontKey)

        val timer = empty.after(Post(persistent("timer", ActivityKind.Timer)), 0)
            .after(Post(transient("bt:1", ActivityKind.Bluetooth, at = 5)), 5)
        assertEquals("timer", timer.frontKey)
    }

    @Test
    fun betterRankedTransientPreemptsAndTheOtherResumes() {
        var s = empty.after(Post(transient("n1", ActivityKind.Notification, displayMs = 10_000)), 0)
        assertEquals("n1", s.frontKey)
        s = s.after(Post(transient("bt:1", ActivityKind.Bluetooth, at = 1_000, displayMs = 3_000)), 1_000)
        assertEquals("bt:1", s.frontKey)
        // BT ends at 4_000; the notification still has time left (ends at 10_000) and resumes.
        s = s.after(Tick, 4_000)
        assertEquals("n1", s.frontKey)
        assertNull(s.after(Tick, 10_000).front)
    }

    @Test
    fun sameRankTransientsAreShownFirstComeFirstServed() {
        var s = empty.after(Post(transient("a", ActivityKind.Notification, at = 0, displayMs = 2_000)), 0)
        s = s.after(Post(transient("b", ActivityKind.Notification, at = 100, displayMs = 2_000, staleAt = 60_000)), 100)
        assertEquals("a", s.frontKey)
        s = s.after(Tick, 2_000)
        assertEquals("b", s.frontKey)
        // b's own display clock starts when it reaches the front, not when it was posted.
        assertEquals(2_000L, s.entries.getValue("b").shownAt)
        assertNull(s.after(Tick, 4_000).front)
    }

    @Test
    fun dismissedTransientDoesNotReopenOnRedelivery() {
        val n = transient("n1", ActivityKind.Notification, revision = 7)
        var s = empty.after(Post(n), 0)
        s = s.after(Dismiss("n1"), 100)
        assertNull(s.front)
        // The same event arriving again (system re-post) stays dismissed...
        s = s.after(Post(n), 200)
        assertNull(s.front)
        // ...but a genuinely new event (higher revision) is shown.
        s = s.after(Post(n.copy(revision = 8)), 300)
        assertEquals("n1", s.frontKey)
    }

    @Test
    fun dismissedMediaStaysHiddenOnMetadataRefreshButReturnsOnNewTrack() {
        val track1 = persistent("media", ActivityKind.Media, revision = 3)
        var s = empty.after(Post(track1), 0)
        s = s.after(Dismiss("media"), 10)
        assertNull(s.front)
        assertTrue("entry is kept so playback state keeps updating", "media" in s.entries)
        // Same revision = same track, e.g. artwork arrived late or position update.
        s = s.after(Post(track1.copy(expiresAt = null)), 20)
        assertNull(s.front)
        // New track = new revision.
        s = s.after(Post(track1.copy(revision = 4)), 30)
        assertEquals("media", s.frontKey)
    }

    @Test
    fun repeatedUpdatesWithSameRevisionDoNotRestartTheDisplayClock() {
        val n = transient("n1", ActivityKind.Notification, revision = 1, displayMs = 4_000)
        var s = empty.after(Post(n), 1_000)
        val shownAt = s.entries.getValue("n1").shownAt
        assertEquals(1_000L, shownAt)
        s = s.after(Post(n.copy(postedAt = 3_000)), 3_000)
        assertEquals(shownAt, s.entries.getValue("n1").shownAt)
        assertNull(s.after(Tick, 5_000).front)
    }

    @Test
    fun identicalPostIsANoOp() {
        val e = persistent("media", ActivityKind.Media)
        val s = empty.after(Post(e), 0)
        assertSame(s.entries, s.after(Post(e), 5).entries)
    }

    @Test
    fun staleOlderRevisionIsIgnored() {
        val e = persistent("media", ActivityKind.Media, revision = 5)
        val s = empty.after(Post(e), 0)
        val after = s.after(Post(e.copy(revision = 4, payload = object : ActivityPayload {})), 1)
        assertSame(s.entries.getValue("media").payload, after.entries.getValue("media").payload)
    }

    @Test
    fun pausedMediaAutoHidesAtItsExpiryAndWakeupIsReported() {
        var s = empty.after(Post(persistent("media", ActivityKind.Media, expiresAt = 8_000)), 0)
        assertEquals(8_000L, ActivityScheduler.nextWakeup(s))
        assertEquals("media", s.after(Tick, 7_999).frontKey)
        s = s.after(Tick, 8_000)
        assertNull(s.front)
        assertNull(ActivityScheduler.nextWakeup(s))
    }

    @Test
    fun resumingPlaybackClearsTheExpiry() {
        var s = empty.after(Post(persistent("media", ActivityKind.Media, expiresAt = 8_000)), 0)
        s = s.after(Post(persistent("media", ActivityKind.Media, expiresAt = null)), 3_000)
        assertEquals("media", s.after(Tick, 60_000).frontKey)
    }

    @Test
    fun timerSurvivesMediaDisappearing() {
        var s = empty.after(Post(persistent("media", ActivityKind.Media, expiresAt = 5_000)), 0)
        s = s.after(Post(persistent("timer", ActivityKind.Timer, at = 1)), 1)
        assertEquals("timer", s.frontKey) // rank 3 beats media rank 6
        s = s.after(Tick, 5_000) // media's auto-hide fires
        assertEquals("timer", s.frontKey)
        assertFalse("media" in s.entries)
    }

    @Test
    fun timerAndStopwatchTieGoesToTheMostRecentlyPosted() {
        var s = empty.after(Post(persistent("timer", ActivityKind.Timer, at = 10)), 10)
        s = s.after(Post(persistent("stopwatch", ActivityKind.Stopwatch, at = 20)), 20)
        assertEquals("stopwatch", s.frontKey)
    }

    @Test
    fun customPriorityOrderIsHonoured() {
        val cfg = PriorityConfig(mapOf(ActivityKind.Media to 1))
        val media = persistent("media", ActivityKind.Media).copy(rank = cfg.rankOf(ActivityKind.Media))
        val timer = persistent("timer", ActivityKind.Timer)
        val s = empty.after(Post(timer), 0).after(Post(media), 1)
        assertEquals("media", s.frontKey)
    }

    @Test
    fun expandedStateFollowsTheFrontEntry() {
        var s = empty.after(Post(persistent("media", ActivityKind.Media)), 0)
        s = s.after(SetExpanded("media", true), 1)
        assertTrue(s.expanded)

        // A notification takes over: media is no longer the expanded front...
        s = s.after(Post(transient("n1", ActivityKind.Notification, displayMs = 2_000)), 2)
        assertFalse(s.expanded)
        // ...and returns expanded, exactly as the user left it, when the notification ends.
        s = s.after(Tick, 2_002)
        assertEquals("media", s.frontKey)
        assertTrue(s.expanded)

        s = s.after(SetExpanded("media", false), 3)
        assertFalse(s.expanded)
    }

    @Test
    fun removingTheExpandedEntryClearsExpansion() {
        var s = empty.after(Post(persistent("media", ActivityKind.Media)), 0)
            .after(SetExpanded("media", true), 1)
        s = s.after(Remove("media"), 2)
        assertNull(s.expandedKey)
        s = s.after(Post(persistent("media", ActivityKind.Media, revision = 2)), 3)
        assertFalse("a fresh session starts collapsed", s.expanded)
    }

    @Test
    fun expandingAnUnknownKeyIsIgnored() {
        assertNull(empty.after(SetExpanded("ghost", true), 0).expandedKey)
    }

    @Test
    fun dismissMemoryIsBounded() {
        var s = empty
        repeat(200) { i ->
            s = s.after(Post(transient("n$i", ActivityKind.Notification, revision = i + 1L, displayMs = 1_000_000)), 0)
            s = s.after(Dismiss("n$i"), 0)
        }
        assertTrue(s.dismissed.size <= 64)
    }

    @Test
    fun callOutranksEverything() {
        var s = empty.after(Post(persistent("timer", ActivityKind.Timer)), 0)
        s = s.after(Post(transient("bt:1", ActivityKind.Bluetooth, at = 1)), 1)
        s = s.after(Post(persistent("call", ActivityKind.Call, at = 2)), 2)
        assertEquals("call", s.frontKey)
    }
}
