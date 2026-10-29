package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class CalibrationCorrectionMapTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun table(gain: Double) = GrupettoCalibrationTable(baseline, List(496) { ln(gain) }, List(496) { 1.0 }, 1, 1, 0.0, 0.0, true)

    @Test fun percentageComesFromGainRegardlessOfWattageAndUnknownRegionsStayGray() {
        val samples = listOf(CalibrationSample(2000.0, 75.0, 200.0, 160.0, 1))
        assertEquals(-20.0, calibrationCorrectionPercent(table(.8), samples)[10 * 16 + 6]!!, .001)
        assertEquals(25.0, calibrationCorrectionPercent(table(1.25), samples)[10 * 16 + 6]!!, .001)
        assertEquals(calibrationCorrectionPercent(table(.8), samples),
            calibrationCorrectionPercent(table(.8), samples.map { it.copy(nativeWatts = 50.0) }))
        assertNull(calibrationCorrectionPercent(table(.8), samples)[30 * 16 + 15])
        assertTrue(calibrationCorrectionPercent(table(.8), emptyList()).all { it == null })
    }

    @Test fun continuousColorScaleHasCorrectSignsMidpointAndSaturation() {
        assertEquals(CorrectionOverColor, correctionColor(-10.0))
        assertEquals(CorrectionOverColor, correctionColor(-100.0))
        assertEquals(CorrectionZeroColor, correctionColor(0.0))
        assertEquals(CorrectionUnderColor, correctionColor(10.0))
        assertEquals(CorrectionUnderColor, correctionColor(100.0))
        assertNotEquals(correctionColor(-5.0), correctionColor(-2.0))
        assertNotEquals(correctionColor(5.0), correctionColor(2.0))
        assertEquals(CorrectionUnknownColor, correctionColor(null))
    }
}
