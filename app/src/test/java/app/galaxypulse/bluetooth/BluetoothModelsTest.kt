package app.galaxypulse.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothModelsTest {

    @Test
    fun classifiesByDeviceClass() {
        assertEquals(DeviceKind.Headphones, DeviceClassifier.classify(0x0400, 0x0418, "Sony WH-1000XM5"))
        assertEquals(DeviceKind.Speaker, DeviceClassifier.classify(0x0400, 0x0414, "JBL Flip"))
        assertEquals(DeviceKind.Watch, DeviceClassifier.classify(0x0700, 0x0704, "Galaxy Watch"))
        assertEquals(DeviceKind.Car, DeviceClassifier.classify(0x0400, 0x0420, "Car Kit"))
    }

    @Test
    fun earbudNamesWinOverClassBecauseBudsReportAsGenericHeadsets() {
        assertEquals(DeviceKind.Earbuds, DeviceClassifier.classify(0x0400, 0x0418, "Galaxy Buds2 Pro"))
        assertEquals(DeviceKind.Earbuds, DeviceClassifier.classify(0x0400, 0x0404, "Pixel Buds"))
        assertEquals(DeviceKind.Earbuds, DeviceClassifier.classify(null, null, "My AirPods"))
    }

    @Test
    fun unknownDevicesAreOtherAndNotRelevant() {
        assertEquals(DeviceKind.Other, DeviceClassifier.classify(0x0500, 0x0540, "Keyboard K380"))
        assertFalse(DeviceClassifier.isRelevant(0x0500, 0x0540, "Keyboard K380"))
        assertTrue(DeviceClassifier.isRelevant(0x0400, 0x0418, "Headphones"))
    }

    @Test
    fun nullsDoNotCrash() {
        assertEquals(DeviceKind.Other, DeviceClassifier.classify(null, null, null))
    }

    @Test
    fun handsFreeNamedCarIsACar() {
        assertEquals(DeviceKind.Car, DeviceClassifier.classify(0x0400, 0x0408, "Mazda Car"))
        assertEquals(DeviceKind.Headphones, DeviceClassifier.classify(0x0400, 0x0408, "Plantronics"))
    }

    @Test
    fun parsesIphoneAccevBatteryOnTheZeroToNineScale() {
        // count=1, key=1 (battery), value=4 -> 50 %
        assertEquals(50, HeadsetBatteryParser.parseIphoneAccev(listOf(1, 1, 4)))
        // battery + dock reports, battery value 9 -> 100 %
        assertEquals(100, HeadsetBatteryParser.parseIphoneAccev(listOf(2, 1, 9, 2, 0)))
        // battery is the second pair
        assertEquals(10, HeadsetBatteryParser.parseIphoneAccev(listOf(2, 2, 0, 1, 0)))
    }

    @Test
    fun ignoresReportsWithoutABatteryKeyOrWithGarbage() {
        assertNull(HeadsetBatteryParser.parseIphoneAccev(listOf(1, 2, 0)))
        assertNull(HeadsetBatteryParser.parseIphoneAccev(listOf(1, 1, 42)))
        assertNull(HeadsetBatteryParser.parseIphoneAccev(emptyList()))
        assertNull(HeadsetBatteryParser.parseIphoneAccev(listOf("x")))
        assertNull(HeadsetBatteryParser.parseIphoneAccev(listOf(3, 1)))
    }

    @Test
    fun batteryEmptinessReflectsWhatTheSystemReported() {
        assertTrue(DeviceBattery().isEmpty)
        assertFalse(DeviceBattery(left = 80).isEmpty)
        assertFalse(DeviceBattery(single = 55).isEmpty)
    }
}
