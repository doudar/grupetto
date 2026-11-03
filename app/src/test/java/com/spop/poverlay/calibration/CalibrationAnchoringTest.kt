package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Tests the persisted, bilinearly interpolated surface, not just fitter control points. */
class CalibrationAnchoringTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun effort(x: Double = 12.37, rpm: Double = 82.4, ratio: Double = 1.16, time: Long = 1) =
        CalibrationSample(1000 + x * 100, rpm, 200.0, 200.0 * ratio, time)
    private fun fit(samples: List<CalibrationSample>) = GrupettoCalibrationFitter.fit(baseline, samples)!!
    private fun assertMatches(table: GrupettoCalibrationTable, sample: CalibrationSample) {
        assertEquals(sample.pedalWatts, table.corrected(sample.nativeWatts, sample.ad, sample.rpm), 1e-8)
        assertTrue(table.gains.all { it.isFinite() && it >= ln(.65) && it <= ln(1.35) })
    }

    @Test fun fractionalObservationIsExactDespiteStrongOpposingPopulatedNeighbors() {
        for (ratio in listOf(.66, 1.34)) {
            val opposite = if (ratio < 1) 1.35 else .65
            val older = (10..14).flatMap { x -> (70..95 step 5).map { rpm ->
                effort(x.toDouble(), rpm.toDouble(), opposite, (x * 100 + rpm).toLong())
            } }
            val latest = effort(ratio = ratio, time = 10_000)
            assertMatches(fit(older + latest), latest)
        }
    }

    @Test fun latestTimestampWinsEvenWhenInputOrderIsReversed() {
        val latest = effort(ratio = 1.21, time = 100)
        val old = effort(ratio = .79, time = 1)
        assertMatches(fit(listOf(latest, old)), latest)
        val forward = fit(listOf(old, latest))
        val reversed = fit(listOf(latest, old))
        forward.gains.indices.forEach { assertEquals(forward.gains[it], reversed.gains[it], 1e-12) }
    }

    @Test fun manyOldRepeatsCannotDiluteOneNewAcceptedEffort() {
        val old = (1L..80L).map { effort(ratio = .73, time = it) }
        val latest = effort(ratio = 1.27, time = 81)
        assertMatches(fit(old + latest), latest)
    }

    @Test fun equalTimestampUsesStableInputOrderForDeterministicLatestWins() {
        val first = effort(ratio = .83, time = 4)
        val last = effort(ratio = 1.18, time = 4)
        assertMatches(fit(listOf(first, last)), last)
    }

    @Test fun gainBoundsAreExactAtFractionalCoordinatesAndSaturateOutOfRangeTargets() {
        for (ratio in listOf(.5, .65, 1.35, 2.0)) {
            val sample = effort(ratio = ratio)
            val table = fit(listOf(sample))
            val expected = sample.copy(pedalWatts = sample.nativeWatts * ratio.coerceIn(.65, 1.35))
            assertMatches(table, expected)
        }
    }

    @Test fun allDomainCornersAndEdgesAnchorWithoutInvalidIndices() {
        for (x in listOf(0.0, 12.37, 30.0)) for (rpm in listOf(45.0, 82.4, 120.0)) {
            val sample = effort(x, rpm)
            assertMatches(fit(listOf(sample)), sample)
        }
    }

    @Test fun nonUniformBaselineUsesPhysicalAdCoordinateRatherThanUniformSpacing() {
        val irregular = List(31) { 1000 + it * it * 20 + it }
        val sample = CalibrationSample(irregular[12] * .63 + irregular[13] * .37, 82.4, 200.0, 226.0, 1)
        val table = GrupettoCalibrationFitter.fit(irregular, listOf(sample))!!
        assertMatches(table, sample)
    }

    @Test fun interpolationRemainsContinuousAcrossGridBoundaries() {
        val table = fit(listOf(effort()))
        val epsilon = 1e-7
        for (x in 1..29) {
            val ad = baseline[x].toDouble()
            assertEquals(table.corrected(200.0, ad - epsilon, 82.4),
                table.corrected(200.0, ad + epsilon, 82.4), 1e-5)
        }
        for (rpm in 50..115 step 5) {
            assertEquals(table.corrected(200.0, 2237.0, rpm - epsilon),
                table.corrected(200.0, 2237.0, rpm + epsilon), 1e-5)
        }
    }

    @Test fun correctionBlendsNearbyButReturnsToNativeFarAway() {
        val sample = effort(5.37, 65.4, 1.2)
        val table = fit(listOf(sample))
        assertMatches(table, sample)
        val nearby = table.corrected(200.0, sample.ad + 200, sample.rpm)
        assertTrue("Nearby points should receive a partial smooth correction: $nearby", nearby > 200 && nearby < 240)
        assertEquals(200.0, table.corrected(200.0, baseline.last().toDouble(), 120.0), 1e-8)
    }

    @Test fun invalidNewerEffortsCannotOverrideLastUsableEffort() {
        val good = effort()
        val invalid = listOf(
            good.copy(ad = Double.NaN), good.copy(ad = 999.0),
            good.copy(rpm = Double.NaN), good.copy(rpm = 44.9), good.copy(rpm = 120.1),
            good.copy(nativeWatts = Double.NaN), good.copy(nativeWatts = 0.0),
            good.copy(pedalWatts = Double.POSITIVE_INFINITY), good.copy(pedalWatts = 4.9),
            good.copy(pedalWatts = good.nativeWatts * 2.01),
            good.copy(pedalWatts = good.nativeWatts * .49)
        ).mapIndexed { index, sample -> sample.copy(capturedAt = index + 100L) }
        val table = fit(listOf(good) + invalid)
        assertEquals(1, table.sampleCount)
        assertMatches(table, good)
        assertNull(GrupettoCalibrationFitter.fit(baseline, invalid))
    }

    @Test fun newerRecordingRevisesEditedSeedWithoutPersistentOffset() {
        val sample = effort(ratio = 1.1)
        val original = fit(listOf(sample))
        val edited = CalibrationManualEdit.setPercent(original, (0 until 496).toSet(), -10.0, false)
        val fresh = sample.copy(pedalWatts = 240.0, capturedAt = 2)
        val updated = GrupettoCalibrationFitter.fit(baseline, listOf(sample, fresh), seed = edited)!!
        assertMatches(updated, fresh)
    }

    @Test fun appliedErrorDiagnosticMeasuresFinalAnchoredSurface() {
        val samples = listOf(effort(ratio = .8), effort(ratio = 1.2, time = 2))
        val incumbent = fit(listOf(effort(ratio = 1.0)))
        val table = GrupettoCalibrationFitter.fit(baseline, samples, incumbent)!!
        val expected = sqrt(samples.map {
            val error = table.corrected(it.nativeWatts, it.ad, it.rpm) - it.pedalWatts
            error * error
        }.average())
        assertEquals(expected, table.activeComparison!!.appliedRmse, 1e-10)
        assertTrue(abs(table.activeComparison!!.appliedRmse) > 0)
    }
}
