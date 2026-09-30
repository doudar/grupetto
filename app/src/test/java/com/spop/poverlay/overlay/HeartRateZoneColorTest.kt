package com.spop.poverlay.overlay

import com.spop.poverlay.ui.theme.*
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateZoneColorTest {
    @Test fun `each boundary starts the next zone`() {
        val zones = listOf(108, 126, 144, 162)
        val colors = listOf(HrZone1Color, HrZone2Color, HrZone3Color, HrZone4Color, HrZone5Color)
        assertEquals(colors[0], heartRateZoneColor(60, zones))
        zones.forEachIndexed { index, boundary ->
            assertEquals(colors[index], heartRateZoneColor(boundary - 1, zones))
            assertEquals(colors[index + 1], heartRateZoneColor(boundary, zones))
        }
        assertEquals(colors.last(), heartRateZoneColor(200, zones))
    }

    @Test fun `custom boundaries replace defaults`() {
        val zones = listOf(90, 100, 110, 120)
        assertEquals(HrZone2Color, heartRateZoneColor(95, zones))
        assertEquals(HrZone5Color, heartRateZoneColor(126, zones))
    }
}
