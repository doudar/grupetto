package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationCaptureIdentityTest {
    private val baseline = List(31) { 1000 + 100 * it }
    private val current = CalibrationCapture("bike", "meter", 2040490, baseline, emptyList())
    private val sample = CalibrationSample(2000.0, 80.0, 200.0, 220.0, 1234)

    @Test fun staleEmptyBaselineCanRecordWithoutClearingThePowerTable() {
        val stale = current.copy(baseline = baseline.mapIndexed { i, value -> value + if (i == 4) 20 else 0 })
        assertEquals(listOf("resistance baseline"), calibrationCaptureMismatches(stale, current))
        assertSame(current, resolveCalibrationCapture(stale, current))
        assertTrue(stale.samples.isEmpty())
    }

    @Test fun eachIdentityMismatchCanRebindOnlyAnEmptyCapture() {
        val mismatches = listOf(
            current.copy(frameSerial = "old-bike") to "bike identity",
            current.copy(serviceVersion = 1) to "service version",
            current.copy(baseline = baseline.map { it + 1 }) to "resistance baseline")
        mismatches.forEach { (stale, field) ->
            assertSame(current, resolveCalibrationCapture(stale, current))
            val recorded = stale.copy(samples = listOf(sample))
            val error = runCatching { resolveCalibrationCapture(recorded, current) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains(field))
            assertTrue(error.message!!.contains("1 efforts were kept"))
            assertEquals(listOf(sample), recorded.samples)
        }
    }

    @Test fun matchingCaptureRetainsAllEffortsAndMetadata() {
        val recorded = current.copy(samples = listOf(sample))
        assertSame(recorded, resolveCalibrationCapture(recorded, current))
        assertSame(current, resolveCalibrationCapture(null, current))
        assertSame(current, resolveCalibrationCapture(current, current))
    }

    @Test fun allMismatchReasonsAreReportedTogether() {
        val stale = current.copy(frameSerial = "old", meterAddress = "old", serviceVersion = 1,
            baseline = baseline.map { it + 1 }, samples = listOf(sample))
        assertEquals(listOf("bike identity", "service version", "resistance baseline"),
            calibrationCaptureMismatches(stale, current))
        assertTrue(runCatching { resolveCalibrationCapture(stale, current) }.isFailure)
    }

    @Test fun anotherMeterKeepsAllExistingEfforts() {
        val recorded = current.copy(samples = listOf(sample), meterAddress = "previous-meter")
        val resolved = resolveCalibrationCapture(recorded, current)
        assertEquals(listOf(sample), resolved.samples)
        assertEquals(current.meterAddress, resolved.meterAddress)
        assertTrue(calibrationCaptureMismatches(recorded, current).isEmpty())
    }

    @Test fun pendingPointsFromAnotherMeterAreMergedOnceAndPersistInOrder() {
        val saved = current.copy(samples = listOf(sample))
        val pending = current.copy(meterAddress = "new-meter", samples = listOf(sample, sample.copy(capturedAt = 2000)))
        val merged = mergeCalibrationCaptures(saved, pending, 0)
        assertEquals(listOf(1234L, 2000L), merged.samples.map { it.capturedAt })
        assertEquals("new-meter", merged.meterAddress)
        assertEquals(merged, mergeCalibrationCaptures(merged, pending, 0))
        assertSame(saved, mergeCalibrationCaptures(saved, pending.copy(frameSerial = "other-bike"), 0))
        assertSame(saved, mergeCalibrationCaptures(saved, pending, 2001))
        assertEquals(listOf(2000L), mergeCalibrationCaptures(current, pending, 1500).samples.map { it.capturedAt })
    }
}
