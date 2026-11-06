package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationRollingReplacementTest {
    private val baseline = List(31) { 1000 + it*100 }
    private fun sample(n: Int, rpm: Double = 80.0) = CalibrationSample(2000.0,rpm,200.0,220.0,
        n*1000L, observation = CalibrationObservation(n*1000L,n*1000L))
    private val cell = calibrationCellIndex(baseline,2000.0,80.0)!!

    @Test fun fillingTo100AndDuplicatePacketsDoNotReportReplacement() {
        val before = (0 until 99).map { sample(it) }
        val full = retainCalibrationObservations(baseline,before+sample(99))
        assertNull(calibrationRollingReplacement(baseline,before,full,cell,100))
        assertNull(calibrationRollingReplacement(baseline,full,
            retainCalibrationObservations(baseline,full+sample(99)),cell,200))
        assertNull(calibrationRollingReplacement(baseline,full,full,null,300))
    }

    @Test fun realRetentionCountsEvictedOldObservationsIncludingConfirmedBatches() {
        val before = (0 until 100).map { sample(it) }
        val retained = retainCalibrationObservations(baseline,before+(100..102).map { sample(it) })
        assertEquals(CalibrationRollingReplacement(cell,999,3),
            calibrationRollingReplacement(baseline,before,retained,cell,999))
        assertEquals((3..102).map { it*1000L },retained.map { it.capturedAt })
        assertNull(calibrationRollingReplacement(baseline,retained,retained,cell,1000))
    }

    @Test fun OtherCellsAndLegacyEvidenceDoNotCreateAReplacementCue() {
        val before = (0 until 100).map { sample(it) } + sample(999).copy(observation = null)
        val retained = retainCalibrationObservations(baseline,before+sample(100,85.0))
        assertNull(calibrationRollingReplacement(baseline,before,retained,cell,1000))
        assertNull(calibrationRollingReplacement(baseline,before,retained,cell+1,1000))
    }
}
