package com.spop.poverlay.sensor.tread

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies the raw -> engineering-unit scaling used by [TreadCombinedSensor]'s
 * `onSensorDataChange` path. These call the real production functions, so a change
 * to the tenths-of-a-unit convention (research doc section 6) breaks the test.
 */
class TreadCombinedSensorTest {

    @Test
    fun `mphFromRaw scales tenths of mph to mph`() {
        // Confirmed pairs from research doc section 6.
        assertEquals(3.2f, mphFromRaw(32), 0.0001f)
        assertEquals(6.7f, mphFromRaw(67), 0.0001f)
    }

    @Test
    fun `inclinePercentFromRaw scales tenths of a percent to percent`() {
        // Confirmed pairs from research doc section 6.
        assertEquals(3.5f, inclinePercentFromRaw(35), 0.0001f)
        assertEquals(8.0f, inclinePercentFromRaw(80), 0.0001f)
    }
}
