package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationCaptureErrorTest {
    private val mismatch = "Bike, power meter or calibration changed. Clear the saved capture to start a new one."

    @Test fun mismatchIsRetainedWithNoSamples() {
        val failed = CalibrationUiState(enabled = true).captureFailed(mismatch)
        assertFalse(failed.enabled)
        assertEquals(0, failed.sampleCount)
        assertEquals(mismatch, failed.error)
        assertEquals(mismatch, failed.status)
    }

    @Test fun failurePreservesExistingCaptureData() {
        val baseline = List(31) { 1000 + 100 * it }
        val recent = listOf(CalibrationSample(2000.0, 80.0, 200.0, 220.0, 1234))
        val failed = CalibrationUiState(enabled = true, sampleCount = 235,
            baseline = baseline, recent = recent, savedAt = 1234).captureFailed(mismatch)
        assertEquals(mismatch, failed.error)
        assertEquals(235, failed.sampleCount)
        assertEquals(baseline, failed.baseline)
        assertEquals(recent, failed.recent)
        assertEquals(1234L, failed.savedAt)
    }

    @Test fun ordinaryPauseHasNoError() {
        val paused = CalibrationUiState(enabled = true).copy(enabled = false, status = "Capture paused")
        assertNull(paused.error)
    }

    @Test fun pauseAndRetryProgressDoNotHideTheFailure() {
        val failed = CalibrationUiState().captureFailed(mismatch)
        assertEquals(mismatch, failed.copy(enabled = false, status = "Capture paused").error)
        assertEquals(mismatch, failed.copy(enabled = true, status = "Reading the bike's calibration table.").error)
    }

    @Test fun successfulIdentityVerificationResolvesErrorWithoutChangingCapture() {
        val retrying = CalibrationUiState(sampleCount = 235).captureFailed(mismatch).copy(enabled = true)
        val recovered = retrying.captureIdentityVerified()
        assertNull(recovered.error)
        assertEquals(retrying.copy(error = null), recovered)
    }

    @Test fun otherFailuresKeepTheirActualReason() {
        val reason = "Could not clear saved capture."
        assertEquals(reason, CalibrationUiState().captureFailed(reason).error)
    }
}
