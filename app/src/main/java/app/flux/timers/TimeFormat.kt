package app.flux.timers

import java.util.Locale
import kotlin.math.max

/** Digits are always ASCII (Locale.ROOT) so timers look the same regardless of the device locale. */
object TimeFormat {

    /** 3:05, 12:00, 1:02:03 — for countdowns. Rounds *up* so 0:00 means done. */
    fun clockCeil(ms: Long): String = clock(ceilToSecond(ms))

    /** Same layout, truncating — for elapsed/position values where 0:59.9 should read 0:59. */
    fun clockFloor(ms: Long): String = clock(max(0L, ms) / 1000)

    private fun ceilToSecond(ms: Long): Long = (max(0L, ms) + 999) / 1000

    private fun clock(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** 03:05.42 / 1:02:03.42 — stopwatch with centiseconds. */
    fun stopwatch(ms: Long): String {
        val t = max(0L, ms)
        val centis = (t % 1000) / 10
        val totalSeconds = t / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, centis)
        else String.format(Locale.ROOT, "%02d:%02d.%02d", m, s, centis)
    }

    /** "less than a minute", "25 min", "1 h", "1 h 5 min" — for estimates such as time-to-full. */
    fun approximate(ms: Long): String {
        val minutes = (max(0L, ms) + 30_000L) / 60_000L
        return when {
            minutes <= 0L -> "less than a minute"
            minutes < 60L -> "$minutes min"
            minutes % 60L == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }
}
