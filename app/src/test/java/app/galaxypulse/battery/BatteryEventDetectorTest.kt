package app.galaxypulse.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryEventDetectorTest {

    private fun reading(level: Int, plugged: Boolean = false, full: Boolean = false) =
        BatteryReading(level, plugged, full, if (plugged) PlugType.Usb else null, temperatureC = 30f)

    private fun detector(threshold: Int = 15) = BatteryEventDetector { threshold }

    @Test
    fun firstReadingProducesNoPlugEvent() {
        assertTrue(detector().onReading(reading(80, plugged = true)).isEmpty())
    }

    @Test
    fun pluggingInFiresChargingStartedOnce() {
        val d = detector()
        d.onReading(reading(50))
        val first = d.onReading(reading(50, plugged = true))
        assertEquals(1, first.size)
        assertTrue(first.single() is BatteryEvent.ChargingStarted)
        // Level ticks while charging must not repeat the event.
        assertTrue(d.onReading(reading(51, plugged = true)).isEmpty())
        assertTrue(d.onReading(reading(52, plugged = true)).isEmpty())
    }

    @Test
    fun unpluggingFiresChargingStopped() {
        val d = detector()
        d.onReading(reading(60, plugged = true))
        val events = d.onReading(reading(60))
        assertTrue(events.single() is BatteryEvent.ChargingStopped)
    }

    @Test
    fun becomingFullFiresOnceAndStaysQuietUntilReplugged() {
        val d = detector()
        d.onReading(reading(99, plugged = true))
        assertTrue(d.onReading(reading(100, plugged = true, full = true)).single() is BatteryEvent.BecameFull)
        assertTrue(d.onReading(reading(100, plugged = true, full = true)).isEmpty())
        assertTrue(d.onReading(reading(99, plugged = true)).isEmpty())
        assertTrue("no second announcement while still plugged in", d.onReading(reading(100, plugged = true, full = true)).isEmpty())
        // New plug session.
        d.onReading(reading(100))
        d.onReading(reading(95, plugged = true))
        assertTrue(d.onReading(reading(100, plugged = true, full = true)).any { it is BatteryEvent.BecameFull })
    }

    @Test
    fun alreadyFullAtStartIsNotAnnounced() {
        assertTrue(detector().onReading(reading(100, plugged = true, full = true)).isEmpty())
    }

    @Test
    fun lowBatteryFiresOnceAtThresholdThenCritical() {
        val d = detector(15)
        d.onReading(reading(20))
        assertTrue(d.onReading(reading(16)).isEmpty())
        val low = d.onReading(reading(15)).single() as BatteryEvent.Low
        assertEquals(15, low.levelPercent)
        assertTrue(!low.critical)
        assertTrue("same level again stays quiet", d.onReading(reading(14)).isEmpty())
        val critical = d.onReading(reading(5)).single() as BatteryEvent.Low
        assertTrue(critical.critical)
        assertTrue(d.onReading(reading(4)).isEmpty())
    }

    @Test
    fun lowBatteryIsRearmedAfterChargingAndRecovery() {
        val d = detector(15)
        d.onReading(reading(14))
        d.onReading(reading(14, plugged = true))
        d.onReading(reading(60, plugged = true))
        d.onReading(reading(60))
        assertTrue(d.onReading(reading(15)).single() is BatteryEvent.Low)
    }

    @Test
    fun noLowAlertWhileCharging() {
        val d = detector(15)
        d.onReading(reading(10, plugged = true))
        assertTrue(d.onReading(reading(11, plugged = true)).isEmpty())
    }

    @Test
    fun startingBelowThresholdAnnouncesOnFirstReading() {
        val events = detector(15).onReading(reading(12))
        assertTrue(events.single() is BatteryEvent.Low)
    }

    @Test
    fun customThresholdIsRead() {
        val d = detector(30)
        d.onReading(reading(40))
        assertTrue(d.onReading(reading(30)).single() is BatteryEvent.Low)
    }
}
