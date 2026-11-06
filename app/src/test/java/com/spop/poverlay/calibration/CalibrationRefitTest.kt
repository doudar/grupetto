package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class CalibrationRefitTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val cell = 12 * 16 + 7
    private fun sample(native: Double, pedals: Double, time: Long = 1, x: Int = 12) =
        CalibrationSample(baseline[x].toDouble(), 80.0, native, pedals, time)
    private fun table(multiplier: Double = 1.0, count: Int = 1, extras: Set<Int> = emptySet()) =
        GrupettoCalibrationTable(baseline, List(496) { ln(multiplier) }, List(496) { 0.0 },
            42, 1 + extras.size, 7.0, 6.0, false, recordedThrough = 100,
            manualCoverage = extras, cellSampleCounts = List(496) { if (it == cell || it in extras) count else 0 })
    private fun multiplier(table: GrupettoCalibrationTable, index: Int = cell) = exp(table.gains[index])

    @Test fun nativeWeightedMedianMinimizesWattsRatherThanRatioError() {
        val observations = listOf(sample(100.0, 80.0), sample(300.0, 360.0, 2))
        val result = refitCalibrationTable(table(), observations)!!
        assertEquals(1.2, multiplier(result.table), 1e-12)
        assertEquals(40.0, result.before.meanAbsoluteError, 1e-12)
        assertEquals(-20.0, result.before.meanSignedError, 1e-12)
        assertEquals(sqrt(2000.0), result.before.rmse, 1e-12)
        assertEquals(20.0, result.after.meanAbsoluteError, 1e-12)
        assertEquals(20.0, result.after.meanSignedError, 1e-12)
        assertEquals(sqrt(800.0), result.after.rmse, 1e-12)
        assertEquals(sqrt(2000.0), result.table.beforeRmse, 1e-12)
        assertEquals(result.after.rmse, result.table.afterRmse, 0.0)
        assertEquals(2, result.after.count)
        assertEquals(1, result.fittedCells)
        assertEquals(0, result.cellsAtBounds)
        assertEquals(2, result.table.cellSampleCounts[cell])
    }

    @Test fun exactHalfMedianIntervalProjectsOldValueAndIsIdempotent() {
        val observations = listOf(sample(100.0, 80.0), sample(100.0, 120.0, 2))
        for ((old, expected) in listOf(.7 to .8, 1.1 to 1.1, 1.3 to 1.2)) {
            val original = table(old)
            val result = refitCalibrationTable(original, observations)!!
            assertEquals(expected, multiplier(result.table), 1e-12)
            if (old == expected) assertEquals(original.gains, result.table.gains)
            val again = refitCalibrationTable(result.table, observations)!!
            assertEquals(result.table, again.table)
            assertEquals(result.after, again.after)
        }
    }

    @Test fun medianAndMedianIntervalsRespectBothGainBounds() {
        for ((pedals, expected) in listOf(30.0 to .5, 250.0 to 1.5)) {
            val result = refitCalibrationTable(table(), listOf(sample(100.0, pedals)))!!
            assertEquals(expected, multiplier(result.table), 1e-12)
            assertEquals(1, result.cellsAtBounds)
        }
        val spanning = refitCalibrationTable(table(1.1), listOf(sample(100.0, 30.0), sample(100.0, 250.0, 2)))!!
        assertEquals(1.1, multiplier(spanning.table), 1e-12)
        assertEquals(0, spanning.cellsAtBounds)
    }

    @Test fun originalPedalEvidenceIsUsedEvenWhenEditedTargetAndLiveRatioGateDiffer() {
        val edited = sample(100.0, 100.0).copy(recordedPedalWatts = 300.0)
        val outsideLiveGate = sample(100.0, 300.0, 2)
        val result = refitCalibrationTable(table(), listOf(edited, outsideLiveGate))!!
        assertEquals(1.5, multiplier(result.table), 1e-12)
        assertEquals(150.0, result.after.meanAbsoluteError, 1e-12)
        assertEquals(-150.0, result.after.meanSignedError, 1e-12)
        assertEquals(2, result.after.count)
        assertEquals(100.0, edited.pedalWatts, 0.0)
        assertEquals(300.0, edited.recordedPedalWatts!!, 0.0)
    }

    @Test fun diagnosticEligibilityRejectsInvalidPowersCadenceAndAdButNotRatios() {
        val good = sample(100.0, 300.0)
        val invalid = listOf(good.copy(nativeWatts = 4.9), good.copy(nativeWatts = 1500.1),
            good.copy(pedalWatts = 4.9), good.copy(pedalWatts = 1500.1),
            good.copy(nativeWatts = Double.NaN), good.copy(recordedPedalWatts = Double.POSITIVE_INFINITY),
            good.copy(rpm = 44.9), good.copy(rpm = 120.1), good.copy(rpm = Double.NaN),
            good.copy(ad = 999.0), good.copy(ad = 4001.0), good.copy(ad = Double.NaN))
        assertEquals(listOf(good), eligibleCalibrationErrorSamples(baseline, invalid + good))
        assertNull(refitCalibrationTable(table(), invalid))
        assertEquals(1, refitCalibrationTable(table(), invalid + good)!!.after.count)
        val endpoints = listOf(sample(5.0, 1500.0).copy(ad = 1000.0, rpm = 45.0),
            sample(1500.0, 5.0).copy(ad = 4000.0, rpm = 120.0))
        assertEquals(endpoints, eligibleCalibrationErrorSamples(baseline, endpoints))
    }

    @Test fun emptyAndManualOnlyCellsAreNeverFilledOrChanged() {
        val manual = 20 * 16 + 7
        val original = table(1.1, extras = setOf(manual))
        val observations = listOf(sample(100.0, 80.0), sample(100.0, 130.0, 2, x = 13))
        val result = refitCalibrationTable(original, observations)!!
        assertEquals(1, result.fittedCells)
        for (index in original.gains.indices.filter { it != cell }) {
            assertEquals(original.gains[index], result.table.gains[index], 0.0)
            assertEquals(original.cellSampleCounts[index], result.table.cellSampleCounts[index])
        }
        assertEquals(0, result.table.cellSampleCounts[13 * 16 + 7])
        assertNull(result.table.correctionPercent[13 * 16 + 7])
        assertNotNull(result.table.correctionPercent[manual])
        assertNull(refitCalibrationTable(original, listOf(sample(100.0, 130.0, x = 13))))
    }

    @Test fun metadataAndFutureLiveAveragingWeightsSurviveRefit() {
        val original = table(1.0, count = 7, extras = setOf(20 * 16 + 7))
        val observations = listOf(sample(100.0, 80.0), sample(300.0, 360.0, 2))
        val result = refitCalibrationTable(original, observations)!!
        val fitted = result.table
        assertEquals(original.recordedThrough, fitted.recordedThrough)
        assertEquals(original.ready, fitted.ready)
        assertEquals(original.sampleCount, fitted.sampleCount)
        assertEquals(original.manualCoverage, fitted.manualCoverage)
        assertEquals(sqrt(2000.0), fitted.beforeRmse, 1e-12)
        assertEquals(result.after.rmse, fitted.afterRmse, 0.0)
        assertEquals(7, fitted.cellSampleCounts[cell])
        assertEquals(20.0, fitted.correctionPercent[cell]!!, 1e-10)
        val fresh = sample(200.0, 160.0, 101)
        val next = GrupettoCalibrationFitter.fit(baseline, observations + fresh, seed = fitted)!!
        assertEquals((7 * 1.2 + .8) / 8, multiplier(next), 1e-12)
        assertEquals(8, next.cellSampleCounts[cell])
        val many = refitCalibrationTable(table(), List(30) { sample(100.0, 120.0, it.toLong()) })!!.table
        assertEquals(10, many.cellSampleCounts[cell])
        val ema = GrupettoCalibrationFitter.fit(baseline, listOf(fresh), seed = many)!!
        assertEquals(.9 * 1.2 + .1 * .8, multiplier(ema), 1e-12)
        assertEquals(10, ema.cellSampleCounts[cell])
    }

    @Test fun refittedMultiplierBeatsEveryBruteForceCandidateOnAbsoluteWattError() {
        val fixtures = listOf(
            listOf(100.0 to 80.0, 300.0 to 360.0),
            listOf(5.0 to 1500.0, 1500.0 to 5.0, 100.0 to 110.0),
            listOf(80.0 to 50.0, 90.0 to 120.0, 170.0 to 170.0, 100.0 to 100.0),
            listOf(100.0 to 80.0, 100.0 to 120.0))
        fixtures.forEach { pairs ->
            val efforts = pairs.mapIndexed { index, (native, pedals) -> sample(native, pedals, index.toLong()) }
            val result = refitCalibrationTable(table(), efforts)!!
            for (step in 0..1000) {
                val gain = .5 + step / 1000.0
                val mae = efforts.map { abs(it.nativeWatts * gain - it.measuredPedalWatts) }.average()
                assertTrue("${result.after.meanAbsoluteError} > $mae at gain $gain", result.after.meanAbsoluteError <= mae + 1e-10)
            }
            assertTrue(result.after.meanAbsoluteError <= result.before.meanAbsoluteError + 1e-10)
        }
    }
}
