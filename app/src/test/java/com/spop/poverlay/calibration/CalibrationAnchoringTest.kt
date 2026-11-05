package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Recordings modify one rounded cell; empty cells only borrow the nearest filled value. */
class CalibrationAnchoringTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun effort(x: Double = 12.37, rpm: Double = 82.4, ratio: Double = 1.16, time: Long = 1) =
        CalibrationSample(1000 + x * 100, rpm, 200.0, 200.0 * ratio, time)
    private fun fit(samples: List<CalibrationSample>) = GrupettoCalibrationFitter.fit(baseline, samples)!!
    private fun assertMatches(table: GrupettoCalibrationTable, sample: CalibrationSample) {
        assertEquals(sample.pedalWatts, table.corrected(sample.nativeWatts, sample.ad, sample.rpm), 1e-8)
        assertTrue(table.gains.all { it.isFinite() && it >= ln(.5) && it <= ln(1.5) })
    }

    @Test fun fractionalObservationOnlyChangesItsOwnCellDespiteExtremeNeighbors() {
        for (ratio in listOf(.66, 1.34)) {
            val opposite = if (ratio < 1) 1.35 else .65
            val older = (10..14).flatMap { x -> (70..95 step 5).filterNot { x == 12 && it == 80 }.map { rpm ->
                effort(x.toDouble(), rpm.toDouble(), opposite, (x * 100 + rpm).toLong())
            } }
            val original = fit(older)
            val latest = effort(ratio = ratio, time = 10_000)
            val updated = GrupettoCalibrationFitter.fit(baseline, older + latest, seed = original)!!
            val own = 12 * 16 + 7
            assertMatches(updated, latest)
            assertEquals(1, updated.cellSampleCounts[own])
            for (cell in updated.gains.indices.filter { it != own }) {
                assertEquals(original.gains[cell], updated.gains[cell], 0.0)
                assertEquals(original.cellSampleCounts[cell], updated.cellSampleCounts[cell])
            }
        }
    }

    @Test fun firstTenUseArithmeticMultiplierMeanAndEleventhUsesPointOneWeight() {
        val ratios = listOf(.65, 1.35, .8, 1.2, .9, 1.1, .7, 1.3, 1.0, 1.25)
        for (count in 1..10) {
            val table = fit(ratios.take(count).mapIndexed { i, ratio -> effort(ratio = ratio, time = i.toLong()) })
            assertEquals(ratios.take(count).average(), exp(table.gains[12 * 16 + 7]), 1e-12)
            assertEquals(count, table.cellSampleCounts[12 * 16 + 7])
            assertEquals(1, table.cells)
        }
        val samples = ratios.mapIndexed { i, ratio -> effort(ratio = ratio, time = i.toLong()) }
        val eleventh = effort(ratio = .7, time = 10)
        val expected = .9 * ratios.average() + .1 * .7
        val cold = fit(samples + eleventh)
        val incremental = GrupettoCalibrationFitter.fit(baseline, samples + eleventh, seed = fit(samples))!!
        assertEquals(expected, exp(cold.gains[12 * 16 + 7]), 1e-12)
        assertEquals(cold.gains, incremental.gains)
        assertEquals(10, incremental.cellSampleCounts[12 * 16 + 7])
    }

    @Test fun timestampsDetermineEmaOrderAndActiveTableDoesNotChangeColdFit() {
        val samples = (1L..12L).map { effort(ratio = if (it <= 10) .8 else if (it == 11L) 1.2 else 1.3, time = it) }
        val forward = fit(samples)
        val reversed = fit(samples.reversed())
        val active = fit(listOf(effort(ratio = 1.35)))
        val compared = GrupettoCalibrationFitter.fit(baseline, samples, active)!!
        assertEquals(.9 * (.9 * .8 + .1 * 1.2) + .1 * 1.3, exp(forward.gains[199]), 1e-12)
        assertEquals(forward.gains, reversed.gains)
        assertEquals(forward.gains, compared.gains)
        assertEquals(forward.cellSampleCounts, compared.cellSampleCounts)
    }

    @Test fun equalTimestampObservationsBothContributeToMean() {
        val table = fit(listOf(effort(ratio = .8, time = 4), effort(ratio = 1.2, time = 4)))
        assertEquals(200.0, table.corrected(200.0, 2237.0, 82.4), 1e-8)
        assertEquals(2, table.cellSampleCounts[199])
    }

    @Test fun ratiosAreClampedBeforeAveragingNotAfter() {
        val table = fit(listOf(effort(ratio = .5), effort(ratio = 1.0, time = 2)))
        assertEquals(.75, exp(table.gains[199]), 1e-12)
        val upper = fit(listOf(effort(ratio = 2.0), effort(ratio = 1.0, time = 2)))
        assertEquals(1.25, exp(upper.gains[199]), 1e-12)
        for (ratio in listOf(.5, .65, 1.35, 2.0)) {
            val sample = effort(ratio = ratio)
            assertMatches(fit(listOf(sample)), sample.copy(pedalWatts = 200 * ratio.coerceIn(.5, 1.5)))
        }
    }

    @Test fun cornersAndRoundedHalfCellBoundariesHaveExplicitIndices() {
        val table = fit(listOf(effort()))
        assertEquals(0, table.currentCellIndex(1000.0, 45.0))
        assertEquals(495, table.currentCellIndex(4000.0, 120.0))
        assertEquals(199, table.currentCellIndex(2249.999, 82.49999))
        assertEquals(216, table.currentCellIndex(2250.0, 82.5))
        for ((ad, rpm) in listOf(999.0 to 80.0, 4001.0 to 80.0, 2000.0 to 44.9,
            2000.0 to 120.1, Double.NaN to 80.0, 2000.0 to Double.NaN)) {
            assertNull(table.currentCellIndex(ad, rpm))
            assertEquals(200.0, table.corrected(200.0, ad, rpm), 0.0)
        }
    }

    @Test fun nonUniformBaselineUsesPhysicalAdCoordinateRatherThanUniformSpacing() {
        val irregular = List(31) { 1000 + it * it * 20 + it }
        val sample = CalibrationSample(irregular[12] * .63 + irregular[13] * .37, 82.4, 200.0, 226.0, 1)
        val table = GrupettoCalibrationFitter.fit(irregular, listOf(sample))!!
        assertEquals(199, table.currentCellIndex(sample.ad, sample.rpm))
        assertMatches(table, sample)
    }

    @Test fun emptyCellUsesNearestGridDistanceAndLowerFlatIndexOnTies() {
        val table = fit(listOf(effort(10.0, 80.0, .8), effort(12.0, 80.0, 1.2, 2)))
        assertEquals(167, table.lookupCellIndex(2100.0, 80.0))
        assertEquals(160.0, table.corrected(200.0, 2100.0, 80.0), 1e-8)
        assertEquals(240.0, table.corrected(200.0, 4000.0, 120.0), 1e-8)
        assertEquals(2, table.cellSampleCounts.count { it > 0 })
        assertEquals(2, table.correctionPercent.count { it != null })
        // One resistance step is closer than two cadence steps, regardless of raw AD/RPM units.
        val distance = fit(listOf(effort(11.0, 80.0, .8), effort(12.0, 90.0, 1.2, 2)))
        assertEquals(183, distance.lookupCellIndex(2200.0, 80.0))
        val cadenceTie = fit(listOf(effort(12.0, 75.0, .8), effort(12.0, 85.0, 1.2, 2)))
        assertEquals(198, cadenceTie.lookupCellIndex(2200.0, 80.0))
    }

    @Test fun completelyEmptyTableHasNoLookupAndLeavesNativePowerAlone() {
        val empty = GrupettoCalibrationTable(baseline, List(496) { ln(1.35) }, List(496) { 0.0 },
            0, 0, 0.0, 0.0, false, cellSampleCounts = List(496) { 0 })
        assertNull(empty.lookupCellIndex(2200.0, 80.0))
        assertEquals(200.0, empty.corrected(200.0, 2200.0, 80.0), 0.0)
    }

    @Test fun invalidNewerEffortsCannotChangeCellMeanOrCount() {
        val good = effort()
        val invalid = listOf(good.copy(ad = Double.NaN), good.copy(ad = 999.0),
            good.copy(rpm = Double.NaN), good.copy(rpm = 44.9), good.copy(rpm = 120.1),
            good.copy(nativeWatts = Double.NaN), good.copy(nativeWatts = 0.0),
            good.copy(pedalWatts = Double.POSITIVE_INFINITY), good.copy(pedalWatts = 4.9),
            good.copy(pedalWatts = good.nativeWatts * 2.01), good.copy(pedalWatts = good.nativeWatts * .49)
        ).mapIndexed { index, sample -> sample.copy(capturedAt = index + 100L) }
        val table = fit(listOf(good) + invalid)
        assertEquals(1, table.sampleCount)
        assertEquals(1, table.cellSampleCounts[199])
        assertMatches(table, good)
        assertNull(GrupettoCalibrationFitter.fit(baseline, invalid))
    }

    @Test fun newRecordingAveragesWithEditedSeedWithoutPersistentOffset() {
        val sample = effort(ratio = 1.1)
        val edited = CalibrationManualEdit.setPercent(fit(listOf(sample)), setOf(199), -10.0, false)
        val fresh = sample.copy(pedalWatts = 240.0, capturedAt = 2)
        val updated = GrupettoCalibrationFitter.fit(baseline, listOf(sample, fresh), seed = edited)!!
        assertEquals(210.0, updated.corrected(200.0, sample.ad, sample.rpm), 1e-8)
        assertEquals(2, updated.cellSampleCounts[199])
    }

    @Test fun appliedErrorDiagnosticMeasuresFinalCellMean() {
        val samples = listOf(effort(ratio = .8), effort(ratio = 1.2, time = 2))
        val table = GrupettoCalibrationFitter.fit(baseline, samples, fit(listOf(effort(ratio = 1.0))))!!
        val expected = sqrt(samples.map {
            val error = table.corrected(it.nativeWatts, it.ad, it.rpm) - it.pedalWatts
            error * error
        }.average())
        assertEquals(40.0, expected, 1e-8)
        assertEquals(expected, table.activeComparison!!.appliedRmse, 1e-10)
    }
}
