package com.spop.poverlay.sensor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the Bike+-misdetected-as-Tread bug: PLTN-TTR01 is the shared
 * Topaz *tablet* model shipped on Bike+ (TITAN), Tread (PRISM) and Row (CAESAR), so the
 * model string alone must never select the tread path.
 */
class DeviceSensorSelectionTest {

    @Test
    fun `bike plus on the shared topaz tablet selects bike plus not tread`() {
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "titan")
        )
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01-2", platform = "titan")
        )
    }

    @Test
    fun `tread selects tread`() {
        assertEquals(
            SensorSelection.Tread,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "prism")
        )
        assertEquals(
            SensorSelection.Tread,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "prism-l")
        )
    }

    @Test
    fun `row does not select tread`() {
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "caesar")
        )
    }

    @Test
    fun `missing platform falls back to the bike path`() {
        // Fallback rationale: a Tread on the bike HUD is cosmetic; a Bike+ bound to
        // ITreadInterface is not.
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = null)
        )
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-TTR01", platform = "")
        )
    }

    @Test
    fun `bike gen 1 is unchanged`() {
        assertEquals(
            SensorSelection.BikeV1,
            selectSensorForDevice(true, model = "PLTN-RB1VQ", platform = "v1")
        )
        assertEquals(
            SensorSelection.BikeV1,
            selectSensorForDevice(true, model = "PLTN-RB1VQ", platform = null)
        )
    }

    @Test
    fun `g700 cross trainer is unchanged`() {
        assertEquals(
            SensorSelection.BikePlus,
            selectSensorForDevice(true, model = "PLTN-ATR01", platform = null)
        )
    }

    @Test
    fun `off peloton selects dummy regardless of platform`() {
        assertEquals(
            SensorSelection.Dummy,
            selectSensorForDevice(false, model = "PLTN-TTR01", platform = "prism")
        )
    }
}
