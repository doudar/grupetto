package com.spop.poverlay.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

class SensorSelectionTest {

    @Test
    fun `not running on peloton selects dummy`() {
        assertEquals(
            SensorSelection.Dummy,
            selectSensor(isRunningOnPeloton = false, isTread = false, isBikePlusOrG700 = false)
        )
        // Probe result is irrelevant off-Peloton.
        assertEquals(
            SensorSelection.Dummy,
            selectSensor(isRunningOnPeloton = false, isTread = true, isBikePlusOrG700 = true)
        )
    }

    @Test
    fun `tread probe selects tread before bike branch`() {
        assertEquals(
            SensorSelection.Tread,
            selectSensor(isRunningOnPeloton = true, isTread = true, isBikePlusOrG700 = false)
        )
        // Tread wins even when the model-based bike+ flag also matches (shared tablet).
        assertEquals(
            SensorSelection.Tread,
            selectSensor(isRunningOnPeloton = true, isTread = true, isBikePlusOrG700 = true)
        )
    }

    @Test
    fun `no tread falls back to existing bike choice`() {
        assertEquals(
            SensorSelection.BikePlus,
            selectSensor(isRunningOnPeloton = true, isTread = false, isBikePlusOrG700 = true)
        )
        assertEquals(
            SensorSelection.BikeV1,
            selectSensor(isRunningOnPeloton = true, isTread = false, isBikePlusOrG700 = false)
        )
    }
}
