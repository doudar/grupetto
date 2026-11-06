package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationRecordingGuardTest {
    @Test fun missingMeterRejectsEveryAttemptWithExactFeedback() {
        val messages = mutableListOf<String>()
        repeat(2) { assertFalse(requireCalibrationMeter(false, messages::add)) }
        assertEquals(listOf("Connect a power meter first", "Connect a power meter first"), messages)
    }

    @Test fun readyMeterAllowsRecordingWithoutFeedback() {
        assertTrue(requireCalibrationMeter(true) { error("Unexpected feedback: $it") })
    }

    @Test fun losingMeterBeforeCaptureLoadsRejectsTheSecondCheck() {
        val messages = mutableListOf<String>()
        assertTrue(requireCalibrationMeter(true, messages::add))
        assertFalse(requireCalibrationMeter(false, messages::add))
        assertEquals(listOf("Connect a power meter first"), messages)
    }
}
