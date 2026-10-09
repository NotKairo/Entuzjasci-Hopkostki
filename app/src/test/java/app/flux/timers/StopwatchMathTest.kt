package app.flux.timers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopwatchMathTest {

    private fun clock(elapsed: Long, wall: Long = 1_700_000_000_000L + elapsed, boot: Int = 3) =
        ClockSample(elapsed, wall, boot)

    @Test
    fun idleIsZeroAndRunningCounts() {
        assertEquals(0L, StopwatchMath.elapsedMs(StopwatchState(), clock(5_000)))
        val s = StopwatchMath.start(StopwatchState(), clock(1_000))
        assertEquals(2_500L, StopwatchMath.elapsedMs(s, clock(3_500)))
    }

    @Test
    fun pauseBanksTimeAndResumeContinuesFromIt() {
        var s = StopwatchMath.start(StopwatchState(), clock(0))
        s = StopwatchMath.pause(s, clock(4_000))
        assertEquals(4_000L, StopwatchMath.elapsedMs(s, clock(60_000)))
        s = StopwatchMath.start(s, clock(100_000))
        assertEquals(9_000L, StopwatchMath.elapsedMs(s, clock(105_000)))
    }

    @Test
    fun startingARunningStopwatchDoesNothing() {
        val s = StopwatchMath.start(StopwatchState(), clock(0))
        assertEquals(s, StopwatchMath.start(s, clock(5_000)))
    }

    @Test
    fun elapsedTimeSurvivesARebootViaTheWallClock() {
        val s = StopwatchMath.start(StopwatchState(), ClockSample(10_000, 5_000_000, 3))
        val afterReboot = ClockSample(2_000, 5_090_000, 4) // 90 s of wall time passed
        assertEquals(90_000L, StopwatchMath.elapsedMs(s, afterReboot))
    }

    @Test
    fun lapsRecordCumulativeTimesAndSplits() {
        var s = StopwatchMath.start(StopwatchState(), clock(0))
        s = StopwatchMath.lap(s, clock(10_000))
        s = StopwatchMath.lap(s, clock(25_000))
        s = StopwatchMath.lap(s, clock(30_000))
        val laps = StopwatchMath.laps(s)
        // Newest first.
        assertEquals(listOf(3, 2, 1), laps.map { it.number })
        assertEquals(listOf(5_000L, 15_000L, 10_000L), laps.map { it.splitMs })
        assertEquals(listOf(30_000L, 25_000L, 10_000L), laps.map { it.totalMs })
        assertTrue(laps.first { it.number == 3 }.isFastest)
        assertTrue(laps.first { it.number == 2 }.isSlowest)
    }

    @Test
    fun noFastestOrSlowestBadgeForASingleLapOrEqualSplits() {
        var s = StopwatchMath.start(StopwatchState(), clock(0))
        s = StopwatchMath.lap(s, clock(10_000))
        assertFalse(StopwatchMath.laps(s).single().isFastest)
        s = StopwatchMath.lap(s, clock(20_000))
        assertTrue(StopwatchMath.laps(s).none { it.isFastest || it.isSlowest })
    }

    @Test
    fun lapsOnlyWhileRunningAndNeverTwiceAtTheSameInstant() {
        val idle = StopwatchMath.lap(StopwatchState(), clock(1_000))
        assertTrue(idle.lapTotalsMs.isEmpty())
        var s = StopwatchMath.start(StopwatchState(), clock(0))
        s = StopwatchMath.lap(s, clock(5_000))
        s = StopwatchMath.lap(s, clock(5_000))
        assertEquals(1, s.lapTotalsMs.size)
    }

    @Test
    fun resetClearsEverything() {
        var s = StopwatchMath.start(StopwatchState(), clock(0))
        s = StopwatchMath.lap(s, clock(5_000))
        assertEquals(StopwatchState(), StopwatchMath.reset())
    }
}
