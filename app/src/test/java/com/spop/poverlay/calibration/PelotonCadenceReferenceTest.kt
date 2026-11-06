package com.spop.poverlay.calibration

import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.*
import org.junit.Test

class PelotonCadenceReferenceTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val rexon = PelotonCadenceReference(baseline, PelotonCadenceReference.Model.Rexon)
    private val tonic = PelotonCadenceReference(baseline, PelotonCadenceReference.Model.Tonic)
    private fun sample(rpm: Double, native: Double = 500.0, pedal: Double = 550.0) =
        CalibrationSample(3000.0, rpm, native, pedal, 100000,
            observation = CalibrationObservation(1000, 1000))

    @Test fun referenceTablesReproduceKnownFactoryValuesAndFractionalCadence() {
        assertEquals(471.0, rexon.watts(3000.0, 90.0)!!, 1e-9)
        assertEquals(560.0, rexon.watts(3000.0, 100.0)!!, 1e-9)
        assertEquals(515.5, rexon.watts(3000.0, 95.0)!!, 1e-9)
        assertEquals(471.1, tonic.watts(3000.0, 90.0)!!, 1e-9)
        assertEquals(559.7, tonic.watts(3000.0, 100.0)!!, 1e-9)
        assertEquals(515.4, tonic.watts(3000.0, 95.0)!!, 1e-9)
    }

    @Test fun ninetySevenNormalizesDownToNinetyFiveButNeverChangesOriginalOrDisplayedInput() {
        val original = sample(97.0)
        val pair = rexon.normalize(original)!!
        assertEquals(95.0, pair.rpm, 0.0)
        assertTrue(pair.pedalWatts < 550.0)
        assertEquals(550.0 * 515.5 / 533.3, pair.pedalWatts, 1e-9)
        assertEquals(1.1, pair.pedalWatts / pair.nativeWatts, 1e-9)
        assertEquals(sample(97.0), original)
        val table = GrupettoCalibrationFitter.fit(baseline, listOf(original), cadenceReference = rexon)!!
        assertEquals(550.0, table.corrected(500.0, original.ad, 97.0), 1e-8)
        assertEquals(1, table.cellSampleCounts.count { it > 0 })
    }

    @Test fun ninetyTwoPointFiveNormalizesUpToNinetyFiveAndExactCenterIsIdentity() {
        val low = tonic.normalize(sample(92.5))!!
        assertEquals(95.0, low.rpm, 0.0); assertTrue(low.factor > 1)
        val exact = tonic.normalize(sample(95.0))!!
        assertEquals(1.0, exact.factor, 0.0)
        assertEquals(550.0, exact.pedalWatts, 0.0)
    }

    @Test fun normalizationBalancesCadenceWeightsForLiveFitAndExplicitRefit() {
        val low = sample(93.0, rexon.watts(3000.0, 93.0)!!, rexon.watts(3000.0, 93.0)!!)
        val high = sample(97.0, rexon.watts(3000.0, 97.0)!!, rexon.watts(3000.0, 97.0)!! * 1.2).copy(capturedAt = 101000)
        assertEquals(1.2, streamCellGain(listOf(low, high), 1.1), 1e-9)
        assertEquals(1.1, streamCellGain(listOf(low, high), 1.1, rexon), 1e-9)
        val cell = 20 * 16 + 10
        val seed = GrupettoCalibrationFitter.fit(baseline, listOf(low, high))!!.let { table ->
            table.copy(gains = table.gains.mapIndexed { index, v -> if (index == cell) ln(1.1) else v })
        }
        val refit = refitCalibrationTable(seed, listOf(low, high), rexon)!!
        assertEquals(1.1, exp(refit.table.gains[cell]), 1e-9)
        assertEquals(0.0, refit.table.gains[cell + 1], 0.0)
    }

    @Test fun editedTargetsRetainOriginalEvidenceAndUnknownIdentityFallsBackWithoutGuessing() {
        val edited = sample(97.0).copy(pedalWatts = 600.0, recordedPedalWatts = 550.0)
        assertEquals(600.0 * rexon.normalize(edited)!!.factor, calibrationCalculationPair(edited, rexon).pedalWatts, 1e-9)
        assertEquals(550.0 * rexon.normalize(edited)!!.factor, calibrationCalculationPair(edited, rexon, measured = true).pedalWatts, 1e-9)
        assertEquals(550.0, edited.measuredPedalWatts, 0.0)
        val capture = CalibrationCapture("unknown", "meter", 2040490L, baseline, listOf(edited))
        assertNull(PelotonCadenceReference.forCapture(capture))
        assertEquals(550.0, calibrationCalculationPair(edited, null, measured = true).pedalWatts, 0.0)
        assertEquals(PelotonCadenceReference.Model.Rexon, PelotonCadenceReference.forCapture(capture.copy(frameSerial = "R00000000000000"))!!.model)
        assertEquals(PelotonCadenceReference.Model.Tonic, PelotonCadenceReference.forCapture(capture.copy(frameSerial = "T00000000000000"))!!.model)
        assertNull(rexon.normalize(sample(Double.NaN)))
    }
}
