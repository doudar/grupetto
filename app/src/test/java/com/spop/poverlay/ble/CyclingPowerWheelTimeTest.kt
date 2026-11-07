package com.spop.poverlay.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class CyclingPowerWheelTimeTest {
    @Test
    fun `wheel time is doubled to 1 over 2048 s resolution`() {
        assertEquals(0, cyclingPowerWheelEventTime(0))
        assertEquals(2048, cyclingPowerWheelEventTime(1024))
        assertEquals(2000, cyclingPowerWheelEventTime(1000))
    }

    @Test
    fun `wheel time wraps at 16 bits`() {
        assertEquals(0, cyclingPowerWheelEventTime(32768))
        assertEquals(2, cyclingPowerWheelEventTime(32769))
        assertEquals(0xFFFE, cyclingPowerWheelEventTime(0x7FFF))
    }
}
