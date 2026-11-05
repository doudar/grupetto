package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class CalibrationStreamTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun observation(t: Long, pedal: Double = 220.0, ad: Double = 2000.0) =
        CalibrationSample(ad, 80.0, 200.0, pedal, 100000 + t,
            observation = CalibrationObservation(t, t))
    private fun add(stream: CalibrationStream, t: Long, pedal: Int = 220, rpm: Double = 80.0,
        ad: Int = 2000, frameAt: Long = t, pedalAt: Long = t, meter: String = "meter", resistance: Int = 40) =
        stream.add(CalibrationFrame(frameAt, ad, rpm, 200.0, "bike", 1, resistance), pedal,
            pedalAt, meter, t, 100000 + t)

    @Test fun settlesTwoSecondsThenCollectsDistinctSecondsContinuously() {
        val stream = CalibrationStream(baseline)
        assertTrue(add(stream, 0).isEmpty())
        assertTrue(add(stream, 1000).isEmpty())
        val first = add(stream, 2000).single()
        assertEquals(observation(2000), first)
        assertEquals(CapturePhase.Collecting, stream.progress.phase)
        assertEquals(1, add(stream, 3000).size)
        assertEquals(1, add(stream, 4000).size)
        assertTrue(add(stream, 4250).isEmpty())
        assertTrue(add(stream, 4500, frameAt = 4000, pedalAt = 4000).isEmpty())
        assertTrue(add(stream, 5000, frameAt = 5000, pedalAt = 4000).isEmpty())
        assertEquals(1, add(stream, 5250).size)
    }

    @Test fun cadenceWindowAndRawResistanceChangesRestartSettlingWithoutLosingPriorCellData() {
        val stream = CalibrationStream(baseline)
        add(stream, 0); add(stream, 1000); val a = add(stream, 2000)
        assertTrue(add(stream, 3000, rpm = 86.0).isEmpty())
        assertTrue(add(stream, 4000, rpm = 86.0).isEmpty())
        assertEquals(1, add(stream, 5000, rpm = 86.0).size)
        assertTrue(add(stream, 6000, rpm = 86.0, ad = 2011).isEmpty())
        assertTrue(add(stream, 7000, rpm = 86.0, ad = 2011).isEmpty())
        assertEquals(1, add(stream, 8000, rpm = 86.0, ad = 2011).size)
        assertEquals(1, a.size)
    }

    @Test fun rawKnobMovementMeterSwitchStalenessAndPollingGapRestartGate() {
        val stream = CalibrationStream(baseline)
        add(stream, 0); add(stream, 1000); add(stream, 2000)
        assertTrue(add(stream, 3000, ad = 2011).isEmpty())
        assertTrue(add(stream, 4000, ad = 2011, meter = "other").isEmpty())
        assertTrue(add(stream, 5000, frameAt = 3000).isEmpty())
        assertEquals(CapturePhase.Waiting, stream.progress.phase)
        assertTrue(add(stream, 6000).isEmpty())
        assertTrue(add(stream, 10000).isEmpty())
        assertTrue(add(stream, 11000).isEmpty())
        assertEquals(1, add(stream, 12000).size)
    }

    @Test fun invalidLowFutureAndDisconnectedReadingsNeverBecomeEvidence() {
        val stream = CalibrationStream(baseline)
        for (t in 0L..6000L step 1000) assertTrue(add(stream, t, pedal = 0).isEmpty())
        assertEquals(CapturePhase.LowPower, stream.progress.phase)
        assertTrue(add(stream, 7000, frameAt = 8000).isEmpty())
        assertTrue(add(stream, 8000, rpm = Double.NaN).isEmpty())
        assertTrue(add(stream, 9000, meter = "").isEmpty())
        assertTrue(add(stream, 10000).isEmpty())
        assertTrue(add(stream, 11000).isEmpty())
        assertEquals(1, add(stream, 12000).size)
    }

    @Test fun isolatedGlitchRejectedButThreeConsistentShiftedPairsEstablishNewLevel() {
        val stream = CalibrationStream(baseline)
        val accepted = mutableListOf<CalibrationSample>()
        for (t in 0L..5000L step 1000) accepted += add(stream, t)
        assertTrue(add(stream, 6000, pedal = 260).isEmpty())
        assertEquals(CapturePhase.Outlier, stream.progress.phase)
        accepted += add(stream, 7000)
        assertEquals(5, accepted.size)
        assertTrue(add(stream, 8000, pedal = 250).isEmpty())
        assertTrue(add(stream, 9000, pedal = 252).isEmpty())
        val shift = add(stream, 10000, pedal = 251)
        assertEquals(listOf(250.0, 252.0, 251.0), shift.map { it.measuredPedalWatts })
        assertEquals(listOf(108000L, 109000L, 110000L), shift.map { it.capturedAt })
        assertTrue(shift.first().observation!!.startsNewLevel)
        assertFalse(shift.last().observation!!.startsNewLevel)
        assertEquals(1, add(stream, 11000, pedal = 250).size)
        val table = GrupettoCalibrationFitter.fit(baseline, accepted + shift)!!
        assertEquals(251.0, table.corrected(200.0, 2000.0, 80.0), 1e-8)
    }

    @Test fun inconsistentOutliersAndInterruptedCandidatesCannotFormAFalseNewLevel() {
        val stream = CalibrationStream(baseline, listOf(observation(1)))
        add(stream, 0); add(stream, 1000)
        assertTrue(add(stream, 2000, pedal = 260).isEmpty())
        assertTrue(add(stream, 3000, pedal = 190).isEmpty())
        stream.reset()
        add(stream, 4000); add(stream, 5000)
        assertTrue(add(stream, 6000, pedal = 260).isEmpty())
        assertTrue(add(stream, 7000, pedal = 260).isEmpty())
        assertEquals(3, add(stream, 8000, pedal = 260).size)
    }

    @Test fun eachCellRetainsOneHundredAcceptedSecondsAcrossVisitsAndWallClockGaps() {
        val first = (0L..79L).map { observation(it * 1000) }
        val second = (80L..129L).map { observation(1000000 + it * 1000) }
        val other = (0L..89L).map { observation(it * 1000, ad = 2100.0) }
        val legacy = observation(1).copy(observation = null)
        val retained = retainCalibrationObservations(baseline, first + other + second + second + legacy)
        assertEquals(191, retained.size)
        assertEquals(100, retained.count { it.ad == 2000.0 && it.observation != null })
        assertEquals(90, retained.count { it.ad == 2100.0 })
        assertEquals(legacy, retained.single { it.observation == null })
        assertEquals(130000L, retained.first { it.ad == 2000.0 && it.observation != null }.capturedAt)
    }

    @Test fun streamUsesAbsoluteWattObjectiveAndOnlyDirectCellChanges() {
        val a = observation(1000, 200.0)
        val b = observation(2000, 390.0).copy(nativeWatts = 300.0)
        val table = GrupettoCalibrationFitter.fit(baseline, listOf(a, b))!!
        assertEquals(1.3, exp(table.gains[10 * 16 + 7]), 1e-9)
        assertEquals(1, table.cellSampleCounts.count { it > 0 })
        assertTrue(table.gains.filterIndexed { i, _ -> i != 10 * 16 + 7 }.all { it == 0.0 })
        assertTrue(table.canActivate)
        assertNull(GrupettoCalibrationFitter.fit(baseline, emptyList()))
    }

    @Test fun manualEditDoesNotReplayOldEvidenceAndNewReadingsUpdateSameTable() {
        val capture = CalibrationCapture("bike", "meter", 2040490L, baseline, listOf(observation(1000)))
        val original = updateUnifiedCalibration(capture, null)
        val cell = 10 * 16 + 7
        val edited = applyUnifiedManualEdit(original, CalibrationManualEdit.setPercent(original.table!!,
            setOf(cell), 20.0, false), 102000)
        val replay = updateUnifiedCalibration(edited.capture, edited)
        assertEquals(edited.table!!.gains, replay.table!!.gains)
        assertEquals(220.0, replay.capture.samples.single().measuredPedalWatts, 0.0)
        val fresh = (3L..5L).map { observation(it * 1000, 200.0) }
        val next = updateUnifiedCalibration(edited.capture.copy(samples = edited.capture.samples + fresh), edited)
        assertEquals(1.0, exp(next.table!!.gains[cell]), 1e-9)
        assertEquals(edited.table.manualCoverage, next.table.manualCoverage)
    }

    @Test fun resetDoesNotCountCachedPacketsAgain() {
        val stream = CalibrationStream(baseline)
        add(stream, 0); add(stream, 1000); add(stream, 2000)
        stream.reset()
        add(stream, 2250, frameAt = 2000, pedalAt = 2000)
        assertTrue(add(stream, 3500, frameAt = 2000, pedalAt = 2000).isEmpty())
        assertTrue(add(stream, 4000).isEmpty())
        assertTrue(add(stream, 5000).isEmpty())
        assertEquals(1, add(stream, 6000).size)
    }
}
