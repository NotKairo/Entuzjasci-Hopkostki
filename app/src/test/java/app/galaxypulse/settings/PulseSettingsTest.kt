package app.galaxypulse.settings

import app.galaxypulse.engine.ActivityKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseSettingsTest {

    @Test
    fun defaultPriorityOrderMatchesTheDocumentedRanking() {
        val cfg = PulseSettings().priorityConfig()
        for (kind in ActivityKind.entries) {
            val expected = if (kind in listOf(ActivityKind.Call, ActivityKind.TimerDone, ActivityKind.BatteryAlert)) {
                kind.defaultRank // fixed, not user-orderable
            } else {
                kind.defaultRank
            }
            assertEquals("rank of $kind", expected, cfg.rankOf(kind))
        }
    }

    @Test
    fun reorderingChangesRanksButCallsAndAlertsStayOnTop() {
        val s = PulseSettings(
            priorityOrder = listOf(
                PriorityGroup.Media, PriorityGroup.Notifications, PriorityGroup.Timers,
                PriorityGroup.Bluetooth, PriorityGroup.Charging,
            ),
        )
        val cfg = s.priorityConfig()
        assertEquals(3, cfg.rankOf(ActivityKind.Media))
        assertEquals(4, cfg.rankOf(ActivityKind.Notification))
        assertEquals(5, cfg.rankOf(ActivityKind.Timer))
        assertEquals("timer and stopwatch share a slot", cfg.rankOf(ActivityKind.Timer), cfg.rankOf(ActivityKind.Stopwatch))
        assertTrue(cfg.rankOf(ActivityKind.Call) < cfg.rankOf(ActivityKind.Media))
        assertTrue(cfg.rankOf(ActivityKind.TimerDone) < cfg.rankOf(ActivityKind.Media))
        assertTrue(cfg.rankOf(ActivityKind.BatteryAlert) < cfg.rankOf(ActivityKind.Media))
    }

    @Test
    fun everyUserOrderableKindIsCoveredExactlyOnce() {
        val covered = PriorityGroup.entries.flatMap { it.kinds }
        assertEquals(covered.size, covered.toSet().size)
        assertTrue(covered.containsAll(listOf(ActivityKind.Timer, ActivityKind.Stopwatch, ActivityKind.Bluetooth, ActivityKind.Charging, ActivityKind.Media, ActivityKind.Notification)))
    }
}
