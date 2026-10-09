package app.flux.timers

/**
 * Compact text encoding for persisting timer/stopwatch state in DataStore. Plain Kotlin on purpose
 * (org.json is an Android stub in JVM unit tests). Decoding never throws: anything malformed yields
 * the default idle state, so corrupted storage can't crash the overlay service on boot.
 */
object StateCodec {
    private const val VERSION = "v1"
    private const val SEP = '|'

    fun encode(s: TimerState): String = listOf(
        VERSION, s.phase.name, s.configuredMs, s.totalMs, s.pausedRemainingMs,
        s.endElapsedMs, s.endWallMs, s.bootCount, s.finishedAtWallMs,
    ).joinToString(SEP.toString())

    fun decodeTimer(raw: String?): TimerState {
        if (raw.isNullOrBlank()) return TimerState()
        return try {
            val p = raw.split(SEP)
            if (p.size < 9 || p[0] != VERSION) return TimerState()
            TimerState(
                phase = TimerPhase.valueOf(p[1]),
                configuredMs = p[2].toLong(),
                totalMs = p[3].toLong(),
                pausedRemainingMs = p[4].toLong(),
                endElapsedMs = p[5].toLong(),
                endWallMs = p[6].toLong(),
                bootCount = p[7].toInt(),
                finishedAtWallMs = p[8].toLong(),
            )
        } catch (_: IllegalArgumentException) {
            TimerState()
        }
    }

    fun encode(s: StopwatchState): String = listOf(
        VERSION, s.phase.name, s.accumulatedMs, s.startElapsedMs, s.startWallMs, s.bootCount,
        s.lapTotalsMs.joinToString(","),
    ).joinToString(SEP.toString())

    fun decodeStopwatch(raw: String?): StopwatchState {
        if (raw.isNullOrBlank()) return StopwatchState()
        return try {
            val p = raw.split(SEP)
            if (p.size < 7 || p[0] != VERSION) return StopwatchState()
            StopwatchState(
                phase = StopwatchPhase.valueOf(p[1]),
                accumulatedMs = p[2].toLong(),
                startElapsedMs = p[3].toLong(),
                startWallMs = p[4].toLong(),
                bootCount = p[5].toInt(),
                lapTotalsMs = if (p[6].isEmpty()) emptyList() else p[6].split(",").map { it.toLong() },
            )
        } catch (_: IllegalArgumentException) {
            StopwatchState()
        }
    }
}
