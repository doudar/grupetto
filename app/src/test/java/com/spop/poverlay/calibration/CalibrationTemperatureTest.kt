package com.spop.poverlay.calibration

import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Test

class CalibrationTemperatureTest {
    private val baseline = List(31) { 1000 + 100 * it }
    private val reference = CalibrationThermalCorrection.ReferenceCelsius
    private fun temperature(celsius: Double, at: Long) = CalibrationTemperature(celsius, 65.0, at)
    private fun point(celsius: Double, gain: Double = 1.2, native: Double = 200.0, at: Long = 1000): CalibrationSample {
        val t = temperature(celsius, at)
        return CalibrationSample(2000.0, 95.0, native, native * gain * CalibrationThermalCorrection.factor(t)!!,
            at, observation = CalibrationObservation(at, at), temperature = t)
    }
    private fun fit(points: List<CalibrationSample>, seed: GrupettoCalibrationTable? = null) =
        GrupettoCalibrationFitter.fit(baseline, points, seed = seed)!!

    @Test fun coldAndWarmSamplesLearnSameReferenceGainAndReproduceEachOriginal() {
        val cold = point(reference)
        val warm = point(reference + 25, at = 2000)
        val table = fit(listOf(cold, warm))
        val cell = table.currentCellIndex(cold.ad, cold.rpm)!!
        assertEquals(1.2, exp(table.gains[cell]), 1e-12)
        listOf(cold, warm).forEach { assertEquals(it.measuredPedalWatts, table.correctedSample(it), 1e-9) }
        assertEquals(cold, point(reference)) // Fitting never mutates raw evidence.
        assertEquals(200.0, warm.nativeWatts, 0.0)
    }

    @Test fun outputAppliesThermalFactorExactlyOnceAndAlphaZeroIsIdentity() {
        val p = point(reference + 25)
        val table = fit(listOf(p))
        val f = CalibrationThermalCorrection.factor(p.temperature)!!
        assertEquals(200 * 1.2 * f, table.correctedSample(p), 1e-9)
        assertNotEquals(200 * 1.2 * f * f, table.correctedSample(p), 1e-6)
        assertEquals(1.0, CalibrationThermalCorrection.factor(p.temperature, 0.0)!!, 0.0)
        assertEquals(1.0, CalibrationThermalCorrection.factor(temperature(reference, 1))!!, 0.0)
    }

    @Test fun fittingUsesNativeTimesThermalFactorWeights() {
        val cold = point(reference, gain = 1.0, native = 100.0)
        val hot = point(100.0, gain = 2.0, native = 105.0, at = 2000)
        val fitted = fit(listOf(cold, hot))
        val cell = fitted.currentCellIndex(cold.ad, cold.rpm)!!
        // 100 > 105*.85; native-only weights would incorrectly choose the high ratio.
        assertEquals(1.0, exp(fitted.gains[cell]), 1e-12)
        val refit = refitCalibrationTable(fitted, listOf(cold, hot))!!
        assertEquals(1.0, exp(refit.table.gains[cell]), 1e-12)
    }

    @Test fun referenceGainCanExceedFiftyPercentWhileAppliedGainCannot() {
        val p = point(100.0, gain = 1.7)
        val table = fit(listOf(p))
        val cell = table.currentCellIndex(p.ad, p.rpm)!!
        assertEquals(1.7, exp(table.gains[cell]), 1e-12)
        assertEquals(p.measuredPedalWatts, table.correctedSample(p), 1e-9)
        assertEquals(300.0, table.corrected(200.0, p.ad, p.rpm, temperature(reference, 1)), 1e-9)
        val low = fit(listOf(point(-10.0, gain = .46)))
        assertTrue(low.corrected(200.0, p.ad, p.rpm, temperature(100.0, 1)) >= 100.0)
        assertTrue(table.hasUsableCells)
    }

    @Test fun legacyCellsKeepTheirOldBehaviorAndUnknownEvidenceDoesNotNormalize() {
        val old = point(reference, gain = 1.1).copy(temperature = null)
        val other = old.copy(ad = 2500.0, capturedAt = 2000)
        val seed = fit(listOf(old, other))
        assertTrue(seed.thermalCells.isEmpty())
        assertEquals(220.0, seed.correctedSample(old.copy(temperature = temperature(100.0, 1000))), 1e-9)
        val new = point(reference + 25, at = 3000)
        val next = fit(listOf(old, other, new), seed)
        assertEquals(1, next.thermalCells.size)
        assertEquals(new.pedalWatts, next.correctedSample(new), 1e-9)
        assertEquals(220.0, next.correctedSample(other), 1e-9)
        assertFalse(next.canEvaluate(old))
        val unknown = old.copy(capturedAt = 4000, pedalWatts = 300.0)
        assertEquals(next.gains, fit(listOf(old, other, new, unknown), next).gains)
    }

    @Test fun unknownLookupUsesSavedAnchorInsteadOfAssumingReferenceOrDroppingCorrection() {
        val warm = point(reference + 25)
        val table = fit(listOf(warm))
        assertEquals(warm.pedalWatts, table.corrected(warm.nativeWatts, warm.ad, warm.rpm), 1e-9)
        assertEquals(warm.pedalWatts, table.corrected(warm.nativeWatts, 2100.0, warm.rpm), 1e-9)
    }

    @Test fun manualEditResetAndErrorReplayUseSavedTemperature() {
        val p = point(reference + 25)
        val table = fit(listOf(p))
        val cell = table.currentCellIndex(p.ad, p.rpm)!!
        val profile = GrupettoCalibrationProfile(CalibrationCapture("bike", "pedal", 2040490, baseline, listOf(p)), table)
        assertEquals(0.0, calibrationErrorComparison(profile, profile, profile)!!.afterWatts!!, 1e-9)
        val edited = CalibrationManualEdit.setPercent(table, setOf(cell), 10.0, false)
        val changed = applyUnifiedManualEdit(profile, edited, 2000)
        val evidence = changed.capture.samples.single()
        assertEquals(p.temperature, evidence.temperature)
        assertEquals(p.nativeWatts, evidence.nativeWatts, 0.0)
        assertEquals(p.pedalWatts, evidence.measuredPedalWatts, 0.0)
        assertEquals(edited.correctedSample(p), evidence.pedalWatts, 1e-9)
        assertEquals(changed.table, updateUnifiedCalibration(changed.capture, changed).table)
        val reset = CalibrationManualEdit.resetCorrections(edited)
        assertTrue(reset.thermalCells.isEmpty())
        assertEquals(200.0, reset.correctedSample(p), 0.0)
    }

    @Test fun temperatureNormalizedOutlierRatioDoesNotRejectConsistentWarmShift() {
        val prior = point(reference, gain = 1.1)
        val stream = CalibrationStream(baseline, listOf(prior), CalibrationStreamSettings(settleSeconds = 0,
            outlierTolerancePercent = 1.0))
        val at = 10000L
        val frame = CalibrationFrame(at, 2000, 95.0, 200.0, "bike", 1)
        val accepted = stream.add(frame, 200, at, "pedal", at, at, temperature(reference + 25, at))
        assertEquals(1, accepted.size)
        assertEquals(CapturePhase.Collecting, stream.progress.phase)
        assertEquals(200.0, accepted.single().pedalWatts, 0.0)
    }

    @Test fun staleFutureInvalidTemperatureCannotBeUsedAndConfidenceMarksGaps() {
        val p = point(reference)
        assertFalse(CalibrationThermalCorrection.usable(p.copy(temperature = temperature(reference, 1001))))
        assertFalse(CalibrationThermalCorrection.usable(p.copy(temperature = temperature(reference, -1))))
        assertNull(CalibrationThermalCorrection.factor(temperature(Double.NaN, 0)))
        val model = FlywheelTemperatureModel(0, 1000)
        assertEquals(TemperatureConfidence.AmbientStartAssumed, model.state.confidence)
        model.observe(0, 100.0)
        assertEquals(TemperatureConfidence.InterruptedHistory, model.advance(5000).confidence)
    }

    @Test fun cadenceAndTemperatureNormalizationComposeWithoutChangingRawSample() {
        val sample = point(reference + 25).copy(rpm = 97.0)
        val curve = PelotonCadenceReference(baseline, PelotonCadenceReference.Model.Tonic)
        val normalized = calibrationCalculationPair(sample, curve, thermal = true)
        assertNotEquals(1.0, normalized.factor, 1e-6)
        assertEquals(95.0, normalized.rpm, 0.0)
        assertEquals(1.2, normalized.pedalWatts / normalized.nativeWatts, 1e-12)
        assertEquals(CalibrationThermalCorrection.factor(sample.temperature)!!, normalized.thermalFactor, 0.0)
        assertEquals(97.0, sample.rpm, 0.0)
        assertEquals(200.0, sample.nativeWatts, 0.0)
    }

    @Test fun manualReferenceEditingDoesNotPrematurelyClampWarmGain() {
        val p = point(reference + 50, gain = 1.7)
        val table = fit(listOf(p))
        val cell = table.currentCellIndex(p.ad, p.rpm)!!
        val nudged = CalibrationManualEdit.nudge(table, setOf(cell), 1.0, false)
        assertEquals(1.71, kotlin.math.exp(nudged.gains[cell]), 1e-9)
        val edited = CalibrationManualEdit.setPercent(table, setOf(cell), 72.0, false)
        assertEquals(1.72, kotlin.math.exp(edited.gains[cell]), 1e-9)
        assertEquals(-50.0..50.0, CalibrationManualEdit.percentRange(table, (0 until 496).toSet()))
        assertTrue(edited.corrected(200.0, p.ad, p.rpm, temperature(reference, 1000)) <= 300.0)
    }

    @Test fun manualFillAndSmoothingNeverMixLegacyAndReferenceBases() {
        val thermal = point(reference).copy(ad = 1000.0, rpm = 45.0)
        val legacy = point(reference, gain = .8, at = 2000).copy(ad = 1100.0, rpm = 45.0, temperature = null)
        val table = fit(listOf(thermal, legacy))
        val smoothed = CalibrationManualEdit.smooth(table, setOf(0))
        assertEquals(table.gains[0], smoothed.gains[0], 0.0)
        val filled = CalibrationManualEdit.fillGaps(table)
        assertTrue(1 in filled.thermalCells)
        assertEquals(table.gains[0], filled.gains[1], 1e-12)
        assertFalse(16 in filled.thermalCells)
        assertEquals(table.gains[16], filled.gains[16], 0.0)
        assertTrue(filled.hasUsableCells)
    }
}
