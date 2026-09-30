package com.spop.poverlay.overlay

import org.junit.Assert.*
import org.junit.Test

class ShifterPlacementTest {
    @Test fun `both ends move one eighth of screen width at full deflection`() {
        for ((width, density) in listOf(1920 to 1.5f, 1280 to 1.33125f)) {
            val edge = shifterEdgeInset(width, density, 0f)
            val middle = shifterEdgeInset(width, density, .5f)
            val inward = shifterEdgeInset(width, density, 1f)
            assertEquals(width / 8, inward - edge)
            assertEquals(width / 16, middle - edge)
            val buttonWidth = (ShifterWidthDp * density).toInt()
            for (inset in listOf(edge, middle, inward)) {
                val left = inset
                val right = width - inset - buttonWidth
                assertTrue(left + buttonWidth < right)
                assertEquals(width.toFloat(), left + right + buttonWidth.toFloat(), 0f)
            }
        }
        assertTrue(ShifterHeightDp > ShifterWidthDp)
    }

    @Test fun `invalid positions stay within the allowed travel`() {
        val edge = shifterEdgeInset(1920, 1.5f, 0f)
        for (value in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(edge, shifterEdgeInset(1920, 1.5f, value))
        }
        assertEquals(edge + 240, shifterEdgeInset(1920, 1.5f, 10f))
    }

    @Test fun `ride buttons only show for connected enabled controls outside configuration`() {
        for (connected in listOf(false, true)) for (enabled in listOf(false, true)) {
            assertFalse(showRideShifters(connected, enabled, true))
        }
        assertFalse(showRideShifters(false, true, false))
        assertFalse(showRideShifters(true, false, false))
        assertTrue(showRideShifters(true, true, false))
    }
}
