package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class CalibrationErrorComparisonTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val samples = listOf(CalibrationSample(1500.0, 75.0, 100.0, 110.0, 1),
        CalibrationSample(1800.0, 85.0, 200.0, 170.0, 2))
    private fun profile(gain: Double, ready: Boolean = true) = GrupettoCalibrationProfile(
        CalibrationCapture("bike", "pedals", 2040490, baseline, samples),
        GrupettoCalibrationTable(baseline, List(496) { ln(gain) }, List(496) { 1.0 }, 2, 2, 999.0, 888.0, ready))

    @Test fun averagesAbsoluteErrorsOnTheSameEffortsUsingActualActiveTable() {
        val active = profile(.9).let { it.copy(capture = it.capture.copy(samples = samples.take(1))) }
        val errors = calibrationErrorComparison(profile(1.1), active, active)!!
        assertEquals(20.0, errors.beforeWatts, .0001)
        assertEquals(15.0, errors.afterWatts!!, .0001)
        assertEquals(2, errors.count)
        assertFalse(errors.preview)
    }

    @Test fun offPreviewsActivationChoiceOrUnfinishedDraftWithoutChangingIt() {
        val saved = profile(.9)
        val draft = profile(1.1)
        val preview = calibrationErrorComparison(draft, null, saved)!!
        assertEquals(25.0, preview.afterWatts!!, .0001)
        assertTrue(preview.preview)
        val unfinished = draft.copy(table = draft.table!!.copy(ready = false))
        assertEquals(15.0, calibrationErrorComparison(unfinished, null, saved)!!.afterWatts!!, .0001)
        assertEquals(25.0, calibrationErrorComparison(unfinished, null, null)!!.afterWatts!!, .0001)
    }

    @Test fun cannotCompareAgainstAnUnrelatedActiveTable() {
        val draft = profile(1.1)
        listOf(draft.capture.copy(frameSerial = "other"),
            draft.capture.copy(serviceVersion = 1), draft.capture.copy(baseline = baseline.map { it + 1 })).forEach {
            val errors = calibrationErrorComparison(draft, draft.copy(capture = it), null)!!
            assertEquals(20.0, errors.beforeWatts, .0001)
            assertNull(errors.afterWatts)
        }
    }

    @Test fun emptyDataHasNoScoreAndLowPowerEffortsCountWithoutInvalidPairs() {
        assertNull(calibrationErrorComparison(null, null, null))
        val profile = profile(.8)
        val low = CalibrationSample(1500.0, 75.0, 15.0, 12.0, 3)
        val capture = profile.capture.copy(samples = listOf(low, low.copy(pedalWatts = Double.NaN), low.copy(ad = Double.NaN)))
        val errors = calibrationErrorComparison(profile.copy(capture = capture), null, null)!!
        assertEquals(1, errors.count)
        assertEquals(3.0, errors.beforeWatts, .0001)
        assertEquals(0.0, errors.afterWatts!!, .0001)
        assertNull(calibrationErrorComparison(profile.copy(capture = capture.copy(samples = emptyList())), null, null))
    }
}
