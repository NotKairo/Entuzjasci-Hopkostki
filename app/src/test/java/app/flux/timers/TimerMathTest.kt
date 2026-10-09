package app.flux.timers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerMathTest {

    private fun clock(elapsed: Long, wall: Long = 1_700_000_000_000L + elapsed, boot: Int = 7) =
        ClockSample(elapsed, wall, boot)

    @Test
    fun runningTimerCountsDownFromTheMonotonicClock() {
        val s = TimerMath.start(TimerState(), 60_000, clock(1_000))
        assertEquals(60_000L, TimerMath.remainingMs(s, clock(1_000)))
        assertEquals(45_000L, TimerMath.remainingMs(s, clock(16_000)))
        assertEquals(0L, TimerMath.remainingMs(s, clock(500_000)))
    }

    @Test
    fun wallClockChangesDoNotAffectARunningTimerInTheSameBoot() {
        val s = TimerMath.start(TimerState(), 60_000, clock(1_000, wall = 1_000_000))
        // The user moves the system clock forward by a day.
        val later = ClockSample(elapsedRealtimeMs = 11_000, wallMs = 1_000_000 + 86_400_000, bootCount = 7)
        assertEquals(50_000L, TimerMath.remainingMs(s, later))
    }

    @Test
    fun afterRebootTheWallClockFallbackIsUsed() {
        val s = TimerMath.start(TimerState(), 600_000, ClockSample(5_000, 1_000_000, 7))
        // New boot: elapsedRealtime restarted near zero, 2 minutes of wall time have passed.
        val afterReboot = ClockSample(elapsedRealtimeMs = 30_000, wallMs = 1_120_000, bootCount = 8)
        assertEquals(480_000L, TimerMath.remainingMs(s, afterReboot))
    }

    @Test
    fun settleReanchorsToTheNewBootSoLaterReadsUseTheMonotonicClockAgain() {
        val s = TimerMath.start(TimerState(), 600_000, ClockSample(5_000, 1_000_000, 7))
        val afterReboot = ClockSample(30_000, 1_120_000, 8)
        val settled = TimerMath.settle(s, afterReboot)
        assertEquals(8, settled.bootCount)
        assertEquals(480_000L, TimerMath.remainingMs(settled, afterReboot))
        // 10s later (monotonic) -> 470s, even if the wall clock jumps.
        val later = ClockSample(40_000, 9_999_999_999L, 8)
        assertEquals(470_000L, TimerMath.remainingMs(settled, later))
    }

    @Test
    fun settleFinishesAnExpiredTimerExactlyOnce() {
        val s = TimerMath.start(TimerState(), 10_000, clock(0))
        val done = TimerMath.settle(s, clock(10_000))
        assertEquals(TimerPhase.Finished, done.phase)
        val firstFinish = done.finishedAtWallMs
        assertTrue(firstFinish > 0)
        // Settling again later keeps the original finish time (alarm receiver + service race).
        val again = TimerMath.settle(done, clock(50_000))
        assertEquals(done, again)
    }

    @Test
    fun timerFinishedWhileTheDeviceWasOffIsFinishedOnBoot() {
        val s = TimerMath.start(TimerState(), 60_000, ClockSample(5_000, 1_000_000, 7))
        val afterReboot = ClockSample(1_000, 1_000_000 + 3_600_000, 8)
        assertEquals(TimerPhase.Finished, TimerMath.settle(s, afterReboot).phase)
    }

    @Test
    fun pauseFreezesRemainingAndResumeContinues() {
        var s = TimerMath.start(TimerState(), 60_000, clock(0))
        s = TimerMath.pause(s, clock(20_000))
        assertEquals(TimerPhase.Paused, s.phase)
        assertEquals(40_000L, TimerMath.remainingMs(s, clock(999_999)))
        s = TimerMath.resume(s, clock(100_000))
        assertEquals(TimerPhase.Running, s.phase)
        assertEquals(30_000L, TimerMath.remainingMs(s, clock(110_000)))
    }

    @Test
    fun addTimeExtendsRunningAndPausedTimers() {
        var s = TimerMath.start(TimerState(), 60_000, clock(0))
        s = TimerMath.addTime(s, 60_000, clock(10_000))
        assertEquals(110_000L, TimerMath.remainingMs(s, clock(10_000)))
        assertEquals(120_000L, s.totalMs)

        var p = TimerMath.pause(TimerMath.start(TimerState(), 60_000, clock(0)), clock(30_000))
        p = TimerMath.addTime(p, 30_000, clock(40_000))
        assertEquals(60_000L, TimerMath.remainingMs(p, clock(40_000)))
    }

    @Test
    fun addTimeOnAFinishedTimerStartsAFreshRun() {
        val finished = TimerMath.settle(TimerMath.start(TimerState(), 5_000, clock(0)), clock(5_000))
        val again = TimerMath.addTime(finished, 60_000, clock(6_000))
        assertEquals(TimerPhase.Running, again.phase)
        assertEquals(60_000L, TimerMath.remainingMs(again, clock(6_000)))
    }

    @Test
    fun cancelReturnsToIdleKeepingTheConfiguredDuration() {
        val s = TimerMath.cancel(TimerMath.start(TimerState(), 90_000, clock(0)))
        assertEquals(TimerPhase.Idle, s.phase)
        assertEquals(90_000L, s.configuredMs)
        assertEquals(90_000L, TimerMath.remainingMs(s, clock(1)))
    }

    @Test
    fun progressGoesFromZeroToOne() {
        val s = TimerMath.start(TimerState(), 100_000, clock(0))
        assertEquals(0f, TimerMath.progress(s, clock(0)), 0.001f)
        assertEquals(0.25f, TimerMath.progress(s, clock(25_000)), 0.001f)
        assertEquals(1f, TimerMath.progress(s, clock(200_000)), 0.001f)
        assertEquals(0f, TimerMath.progress(TimerState(), clock(0)), 0.001f)
    }

    @Test
    fun alarmTimeIsOnlyOfferedForARunningTimer() {
        val s = TimerMath.start(TimerState(), 60_000, clock(1_000))
        assertEquals(61_000L, TimerMath.alarmAtElapsedMs(s, clock(1_000)))
        assertNull(TimerMath.alarmAtElapsedMs(TimerMath.pause(s, clock(2_000)), clock(2_000)))
        assertNull(TimerMath.alarmAtElapsedMs(TimerState(), clock(0)))
    }

    @Test
    fun startNeverAcceptsAZeroDuration() {
        val s = TimerMath.start(TimerState(), 0, clock(0))
        assertEquals(1_000L, TimerMath.remainingMs(s, clock(0)))
    }
}
