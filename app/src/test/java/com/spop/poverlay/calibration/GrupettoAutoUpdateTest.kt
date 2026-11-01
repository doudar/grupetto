package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ln

class GrupettoAutoUpdateTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val samples = (6..22 step 2).flatMap { x -> listOf(65, 80, 95).map { rpm ->
        CalibrationSample(baseline[x].toDouble(), rpm.toDouble(), 200.0, 220.0, (x * 100 + rpm).toLong())
    } }
    private fun constant(gain: Double) = GrupettoCalibrationTable(baseline, List(496) { ln(gain) },
        List(496) { 1.0 }, 6, 6, .001, .001, true)

    @Test fun activationCanReuseSavedTableWhenLatestDraftLacksCoverage() {
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, samples)
        val saved = GrupettoCalibrationProfile(capture, constant(1.1))
        val draft = saved.copy(table = saved.table!!.copy(cells = 2, ready = false))
        assertSame(saved, activationProfile(draft, saved))
        assertNull(activationProfile(draft, null))
        assertSame(saved, activationProfile(null, saved))
        val readyDraft = draft.copy(table = constant(1.08))
        assertSame(readyDraft, activationProfile(readyDraft, saved))
    }

    @Test fun savedActivationCannotCrossBikeOrBaselineChanges() {
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, samples)
        val saved = GrupettoCalibrationProfile(capture, constant(1.1))
        listOf(capture.copy(frameSerial = "other"),
            capture.copy(serviceVersion = 1), capture.copy(baseline = baseline.map { it + 1 })).forEach {
            assertNull(activationProfile(GrupettoCalibrationProfile(it, saved.table!!.copy(ready = false)), saved))
        }
    }

    @Test fun comparesAgainstActualActivePredictionsOnTheSameEffortsNotHistoricalScores() {
        val fit = GrupettoCalibrationFitter.fit(baseline, samples, constant(1.0))!!
        assertTrue(fit.ready)
        val comparison = fit.activeComparison!!
        assertEquals(20.0, comparison.activeRmse, .001)
        assertEquals(samples.size, comparison.count)
        assertEquals(fit.afterRmse, comparison.candidateRmse, 0.0)
        assertTrue(comparison.improved)
    }

    @Test fun newFitReplacesEvenAnAlreadyBetterActiveTable() {
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, samples)
        val active = GrupettoCalibrationProfile(capture, constant(1.10))
        val latest = GrupettoCalibrationProfile(capture, GrupettoCalibrationFitter.fit(baseline, samples, active.table))
        assertFalse(latest.table!!.activeComparison!!.improved)
        assertSame(latest, automaticCalibrationProfile(latest, active))
        val incumbent = GrupettoCalibrationFitter.fit(baseline, samples)!!
        val repeated = GrupettoCalibrationFitter.fit(baseline, samples, incumbent)!!
        assertFalse(repeated.activeComparison!!.improved)
        assertNotNull(automaticCalibrationProfile(GrupettoCalibrationProfile(capture, repeated), active))
    }

    @Test fun diagnosticComparisonReportsImprovementWithoutWattOrPercentageThresholds() {
        assertTrue(ActiveCalibrationComparison(10.0, 9.999999, 20, 0).improved)
        assertTrue(ActiveCalibrationComparison(30.0, 29.999999, 20, 0).improved)
        assertTrue(ActiveCalibrationComparison(10.0, 8.0, 20, 1).improved)
        assertFalse(ActiveCalibrationComparison(10.0, 10.0, 20, 0).improved)
        assertFalse(ActiveCalibrationComparison(10.0, 8.0, 5, 0).improved)
        assertFalse(ActiveCalibrationComparison(10.0, 8.0, 20, 0, appliedRmse = 10.5).improved)
        assertFalse(ActiveCalibrationComparison(Double.NaN, 8.0, 20, 0).improved)
        assertFalse(ActiveCalibrationComparison(10.0, -1.0, 20, 0).improved)
        assertTrue(ActiveCalibrationComparison(10.0, 9.5, 20, 0).improved)
    }

    @Test fun smallCorrectionsAndEqualErrorAreBothReadyWithEnoughCoverage() {
        val tiny = samples.map { it.copy(pedalWatts = it.nativeWatts + .1) }
        val table = GrupettoCalibrationFitter.fit(baseline, tiny)!!
        assertTrue(table.beforeRmse < 1.0)
        assertTrue(table.afterRmse < table.beforeRmse)
        assertTrue(table.ready)
        val equal = GrupettoCalibrationFitter.fit(baseline, samples.map { it.copy(pedalWatts = it.nativeWatts) })!!
        assertTrue(equal.ready)
    }

    @Test fun worseValidationErrorDoesNotBlockAUsableFit() {
        val alternating = samples.mapIndexed { i, sample ->
            sample.copy(pedalWatts = sample.nativeWatts + if (i % 2 == 0) 20.0 else -20.0)
        }
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, alternating)
        val fit = GrupettoCalibrationFitter.fit(baseline, alternating)!!
        assertTrue("Expected non-improving fixture: ${fit.beforeRmse} -> ${fit.afterRmse}", fit.afterRmse >= fit.beforeRmse)
        assertTrue(fit.ready)
        val latest = GrupettoCalibrationProfile(capture, fit)
        assertSame(latest, automaticCalibrationProfile(latest, GrupettoCalibrationProfile(capture, constant(1.0))))
    }

    @Test fun automaticUpdateStillRequiresActivationMatchingIdentityAndCoverage() {
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, samples)
        val profile = GrupettoCalibrationProfile(capture, constant(1.1))
        assertNull(automaticCalibrationProfile(profile, null))
        assertNull(automaticCalibrationProfile(profile.copy(table = profile.table!!.copy(ready = false)), profile))
        listOf(capture.copy(frameSerial = "other"),
            capture.copy(serviceVersion = 1), capture.copy(baseline = baseline.map { it + 1 })).forEach {
            assertNull(automaticCalibrationProfile(profile.copy(capture = it), profile))
        }
    }

    @Test fun differentBikeOrBaselineCannotSupplyAnIncumbentForAutoUpdate() {
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, samples)
        val profile = GrupettoCalibrationProfile(capture, constant(1.0))
        assertTrue(profile.matches(capture.copy(samples = samples.take(6))))
        assertFalse(profile.matches(capture.copy(frameSerial = "different")))
        assertTrue(profile.matches(capture.copy(meterAddress = "different")))
        assertFalse(profile.matches(capture.copy(serviceVersion = 1)))
        val other = baseline.map { it + 1 }
        assertFalse(profile.matches(capture.copy(baseline = other)))
        assertNull(GrupettoCalibrationFitter.fit(baseline, samples, constant(1.0).copy(baseline = other))!!.activeComparison)
    }

    @Test fun anotherMeterCanUpdateAndActivateExistingCalibration() {
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, samples)
        val saved = GrupettoCalibrationProfile(capture, constant(1.1))
        val latest = saved.copy(capture = capture.copy(meterAddress = "new-meter"))
        assertSame(latest, automaticCalibrationProfile(latest, saved))
        assertSame(saved, activationProfile(latest.copy(table = latest.table!!.copy(ready = false)), saved))
    }
}
