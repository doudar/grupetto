package com.spop.poverlay.sensor.power

import com.spop.poverlay.ble.FitnessMachineData
import org.junit.Assert.*
import org.junit.Test

class CalibrationPowerStatusTest {
    @Test fun externalSensorLabelReflectsActivationRecordingAndConnectionIndependently() {
        assertEquals("Using calibrated built-in power", calibrationSensorLabel(true, false, null))
        assertEquals("Using calibrated built-in power", calibrationSensorLabel(true, false, "Pedals"))
        assertEquals("Using calibrated built-in power and calibrating built-in power", calibrationSensorLabel(true, true, "Pedals"))
        assertEquals("Pedals and calibrating built-in power", calibrationSensorLabel(false, true, "Pedals"))
        assertEquals("Using built-in power", calibrationSensorLabel(false, true, null))
    }
    @Test fun bleAndDirConFtmsPayloadUsesTheSameIntegerAsTheOverlay() {
        for (watts in listOf(0f, 1.5f, 145.49f, 195.5f, 202.9f, 1500f)) {
            val packet = FitnessMachineData.encode(80f, watts, 15f, 40f)
            val transmitted = (packet[8].toInt() and 255) or ((packet[9].toInt() and 255) shl 8)
            assertEquals(telemetryWatts(watts), transmitted)
        }
    }
}
