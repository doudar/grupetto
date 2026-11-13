package com.spop.poverlay

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateZoneMathTest {
    private val none = listOf<Int?>(null, null, null, null, null)
    private val defaults = listOf<Int?>(108, 126, 144, 162, 180)

    @Test
    fun `one box fills the blank rest at 60 70 80 90 100 percent of max`() {
        assertEquals(defaults, fillZoneBoundaries(none, listOf(null, 126, null, null, null), 1))
        assertEquals(defaults, fillZoneBoundaries(none, listOf(108, null, null, null, null), 0))
        assertEquals(defaults, fillZoneBoundaries(none, listOf(null, null, null, null, 180), 4))
    }

    @Test
    fun `zone 1 at 106 gives about 124 141 159 and 177`() {
        // Max is 176.67, so the exact values are 123.67, 141.33, 159 and 176.67.
        assertEquals(
            listOf<Int?>(106, 124, 141, 159, 177),
            fillZoneBoundaries(none, listOf(106, null, null, null, null), 0)
        )
    }

    @Test
    fun `changing one of an automatic set moves the whole set`() {
        // Max 190: 114, 133, 152, 171, 190
        assertEquals(
            listOf<Int?>(114, 133, 152, 171, 190),
            fillZoneBoundaries(defaults, listOf(108, 133, 144, 162, 180), 1)
        )
    }

    @Test
    fun `boxes typed by hand are left alone`() {
        // 100 is not the automatic value for 126, so it stays; the automatic ones follow.
        assertEquals(
            listOf<Int?>(100, 133, 152, 171, 190),
            fillZoneBoundaries(listOf(100, 126, 144, 162, 180), listOf(100, 133, 144, 162, 180), 1)
        )
    }

    @Test
    fun `blank or zero anchor changes nothing`() {
        assertEquals(none, fillZoneBoundaries(none, none, 2))
        val zero = listOf<Int?>(0, null, null, null, null)
        assertEquals(zero, fillZoneBoundaries(none, zero, 0))
    }

    @Test
    fun `wrong sized lists are returned unchanged`() {
        val short = listOf<Int?>(126)
        assertEquals(short, fillZoneBoundaries(short, short, 0))
    }

    @Test
    fun `tapping into a box without changing it recalculates nothing`() {
        // 160 was entered in the last box, giving 96 for zone 1. Tapping 96 must not move 160.
        val filled = fillZoneBoundaries(none, listOf(null, null, null, null, 160), 4)
        assertEquals(listOf<Int?>(96, 112, 128, 144, 160), filled)
        assertEquals(filled, fillZoneBoundaries(filled, filled, 0))
    }
}
