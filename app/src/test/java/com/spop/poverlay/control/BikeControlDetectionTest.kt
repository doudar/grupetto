package com.spop.poverlay.control

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BikeControlDetectionTest(private val peloton: Boolean, private val platform: String?,
    private val model: String, private val expected: Boolean) {
    @Test fun motorCapabilityIsIndependentOfTabletSensorRouting() {
        assertEquals(expected, supportsBikeControl(peloton, platform, model))
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} {1} {2} -> {3}")
        fun paths() = listOf(
            arrayOf(true, "titan", "PLTN-TTR01", true),
            arrayOf(true, " TITAN ", "PLTN-TTR01-2", true),
            arrayOf(true, null, "G700", true),
            arrayOf(true, "", "peloton-g700", true),
            arrayOf(true, null, "PLTN-ATR01", true),
            arrayOf(true, "unknown", "pltn-atr02", true),
            arrayOf(true, "prism", "PLTN-TTR01", false),
            arrayOf(true, "prism-l", "PLTN-TTR01-2", false),
            arrayOf(true, "caesar", "PLTN-TTR01", false),
            arrayOf(true, "aurora", "PLTN-ATR01", false),
            arrayOf(true, "v1", "PLTN-PL01", false),
            arrayOf(true, null, "PLTN-TTR01", false),
            arrayOf(true, "", "PLTN-TTR01", false),
            arrayOf(true, "unknown", "PLTN-TTR01", false),
            arrayOf(true, "titanium", "PLTN-TTR01", false),
            arrayOf(false, "titan", "PLTN-TTR01", false),
            arrayOf(false, null, "G700", false),
            arrayOf(false, null, "Emulator", false)
        )
    }
}
