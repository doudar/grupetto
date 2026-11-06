package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class CalibrationCorrectionLimitsTest {
    private val baseline = List(31) { 1000 + it * 100 }

    @Test fun recordingAndStreamingUseFiftyPercentBoundsAndRemainActivatable() {
        for ((ratio, expected) in listOf(.5 to .5, 1.5 to 1.5, 1.7 to 1.5)) {
            for (stream in listOf(false, true)) {
                val pair = CalibrationSample(2000.0, 95.0, 200.0, 200 * ratio, 1000,
                    observation = if (stream) CalibrationObservation(1, 1) else null)
                val table = GrupettoCalibrationFitter.fit(baseline, listOf(pair))!!
                assertTrue(table.canActivate)
                assertEquals(expected * 200, table.corrected(200.0, 2000.0, 95.0), 1e-10)
                assertEquals(1, table.cellSampleCounts.count { it > 0 })
                assertEquals(200 * ratio, pair.measuredPedalWatts, 0.0)
            }
        }
    }

    @Test fun runtimeClampsOutOfBoundsButActivationRejectsMalformedGains() {
        val pair = CalibrationSample(2000.0, 95.0, 200.0, 200.0, 1000)
        val table = GrupettoCalibrationFitter.fit(baseline, listOf(pair))!!
        val cell = table.currentCellIndex(2000.0, 95.0)!!
        for ((bad, expected) in listOf(.49 to 100.0, 1.51 to 300.0)) {
            val invalid = table.copy(gains = table.gains.toMutableList().apply { this[cell] = ln(bad) })
            assertFalse(invalid.canActivate)
            assertEquals(expected, invalid.corrected(200.0, 2000.0, 95.0), 1e-10)
        }
    }
}
