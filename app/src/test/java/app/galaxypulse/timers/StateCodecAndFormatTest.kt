package app.galaxypulse.timers

import org.junit.Assert.assertEquals
import org.junit.Test

class StateCodecAndFormatTest {

    @Test
    fun timerStateRoundTrips() {
        val s = TimerState(
            phase = TimerPhase.Running, configuredMs = 123_000, totalMs = 180_000,
            pausedRemainingMs = 0, endElapsedMs = 99_999, endWallMs = 1_700_000_123_456,
            bootCount = 42, finishedAtWallMs = 0,
        )
        assertEquals(s, StateCodec.decodeTimer(StateCodec.encode(s)))
    }

    @Test
    fun stopwatchStateRoundTripsIncludingLaps() {
        val s = StopwatchState(
            phase = StopwatchPhase.Paused, accumulatedMs = 12_345, startElapsedMs = 1, startWallMs = 2,
            bootCount = 9, lapTotalsMs = listOf(1_000, 2_500, 7_000),
        )
        assertEquals(s, StateCodec.decodeStopwatch(StateCodec.encode(s)))
        val noLaps = s.copy(lapTotalsMs = emptyList())
        assertEquals(noLaps, StateCodec.decodeStopwatch(StateCodec.encode(noLaps)))
    }

    @Test
    fun corruptedStorageFallsBackToIdleInsteadOfCrashing() {
        assertEquals(TimerState(), StateCodec.decodeTimer(null))
        assertEquals(TimerState(), StateCodec.decodeTimer(""))
        assertEquals(TimerState(), StateCodec.decodeTimer("garbage"))
        assertEquals(TimerState(), StateCodec.decodeTimer("v1|Running|notanumber|1|1|1|1|1|1"))
        assertEquals(TimerState(), StateCodec.decodeTimer("v9|Running|1|1|1|1|1|1|1"))
        assertEquals(TimerState(), StateCodec.decodeTimer("v1|Exploding|1|1|1|1|1|1|1"))
        assertEquals(StopwatchState(), StateCodec.decodeStopwatch("v1|Running|1"))
        assertEquals(StopwatchState(), StateCodec.decodeStopwatch("v1|Paused|1|1|1|1|1,x,3"))
    }

    @Test
    fun countdownRoundsUpSoZeroMeansDone() {
        assertEquals("1:00", TimeFormat.clockCeil(60_000))
        assertEquals("1:00", TimeFormat.clockCeil(59_001))
        assertEquals("0:01", TimeFormat.clockCeil(1))
        assertEquals("0:00", TimeFormat.clockCeil(0))
        assertEquals("0:00", TimeFormat.clockCeil(-5))
        assertEquals("1:02:03", TimeFormat.clockCeil(3_723_000))
    }

    @Test
    fun positionsTruncate() {
        assertEquals("0:59", TimeFormat.clockFloor(59_999))
        assertEquals("3:05", TimeFormat.clockFloor(185_400))
        assertEquals("0:00", TimeFormat.clockFloor(-1))
    }

    @Test
    fun stopwatchShowsCentiseconds() {
        assertEquals("00:00.00", TimeFormat.stopwatch(0))
        assertEquals("01:05.42", TimeFormat.stopwatch(65_420))
        assertEquals("1:01:01.09", TimeFormat.stopwatch(3_661_090))
    }
}
