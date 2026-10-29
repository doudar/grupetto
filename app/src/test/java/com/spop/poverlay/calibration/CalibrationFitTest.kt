package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CalibrationFitTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun samples(offset: (Int) -> Double = { 20.0 }, repeats: Int = 3,
                        cadences: List<Int> = listOf(70, 90)) = cadences.flatMap { rpm ->
        (1..29).flatMap { i -> List(repeats) { repeat ->
            val ad = baseline[i].toDouble()
            val native = (ad - 1000) * .25 * rpm / 80 + 40
            CalibrationSample(ad, rpm.toDouble(), native, native - offset(rpm) * .25 * rpm / 80,
                (rpm * 100000 + i * 1000 + repeat * 12).toLong())
        } }
    }
    @Test fun learnsKnownSensorOffsetAcrossCadencesAndValidatesOnSeparateEfforts() {
        val fit = CalibrationFitter.fit(baseline, samples())
        assertTrue(fit.message, fit.ready)
        assertTrue(fit.supportedPoints.size >= 20)
        fit.supportedPoints.forEach { assertEquals(baseline[it] + 20, fit.points[it]) }
        assertTrue(fit.afterRmse!! < 0.01)
        assertTrue(fit.beforeRmse!! > 4)
        assertTrue(fit.validationCount >= 12)
        assertEquals(baseline.first(), fit.points.first())
        assertEquals(baseline.last(), fit.points.last())
    }
    @Test fun oppositeCadenceErrorsAreReportedRatherThanAveragedAway() {
        val fit = CalibrationFitter.fit(baseline, samples({ if (it == 70) 20.0 else -20.0 }))
        assertTrue(fit.conflicts > 0)
        assertEquals(baseline, fit.points)
        assertFalse(fit.ready)
    }
    @Test fun aSingleCadenceCannotProduceAReadyCalibration() {
        val fit = CalibrationFitter.fit(baseline, samples(cadences = listOf(80)))
        assertEquals(baseline, fit.points)
        assertFalse(fit.ready)
    }
    @Test fun repeatsUsedForTrainingCannotAlsoCountAsValidation() {
        val fit = CalibrationFitter.fit(baseline, samples(repeats = 2))
        assertEquals(0, fit.validationCount)
        assertFalse(fit.ready)
    }
    @Test fun noDataNeverInventsPoints() {
        val fit = CalibrationFitter.fit(baseline, emptyList())
        assertEquals(baseline, fit.points)
        assertTrue(fit.supportedPoints.isEmpty())
    }
    @Test fun wideUnobservedResistanceGapsAreNotTreatedAsCovered() {
        val sparse = samples().filter { it.ad in listOf(1100.0, 2500.0, 3900.0) }
        val fit = CalibrationFitter.fit(baseline, sparse)
        assertEquals(baseline, fit.points)
        assertTrue(fit.supportedPoints.isEmpty())
    }
    @Test fun separateValidationDisagreementPreventsReadyStatus() {
        val data = samples().mapIndexed { index, sample ->
            if (index % 3 == 2) sample.copy(pedalWatts = sample.nativeWatts + 10) else sample
        }
        val fit = CalibrationFitter.fit(baseline, data)
        assertFalse(fit.ready)
        assertTrue(fit.afterRmse!! > fit.beforeRmse!!)
    }
    @Test fun excessiveOffsetsAreNotClippedIntoCandidates() {
        val fit = CalibrationFitter.fit(baseline, samples({ 90.0 }))
        assertTrue(fit.supportedPoints.isEmpty())
        assertFalse(fit.ready)
    }
    @Test fun alreadyMatchingPowerDoesNotRequestAnUpdate() {
        val fit = CalibrationFitter.fit(baseline, samples({ 0.0 }))
        assertEquals(baseline, fit.points)
        assertFalse(fit.ready)
    }
    @Test fun duplicateAndOverflowProneTablePointsAreRejected() {
        assertFalse(validCalibrationTable(List(31) { 1000 }))
        assertFalse(validCalibrationTable(List(31) { Int.MAX_VALUE - 30 + it }))
        assertFalse(validCalibrationTable(emptyList()))
    }
    @Test fun nonFiniteSamplesDoNotPoisonFit() {
        val fit = CalibrationFitter.fit(baseline, samples() +
            CalibrationSample(Double.NaN, 80.0, 200.0, 200.0, 1))
        assertTrue(fit.ready)
        assertTrue(fit.points.zip(baseline).all { abs(it.first - it.second) <= 20 })
    }
}
