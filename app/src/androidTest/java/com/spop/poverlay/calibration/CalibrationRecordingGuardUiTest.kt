package com.spop.poverlay.calibration

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalibrationRecordingGuardUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordTapWithoutMeterGivesFeedbackAndStaysOffThenAllowsConnectAndPause() {
        val ready = mutableStateOf(false)
        val state = mutableStateOf(GrupettoCalibrationUiState())
        val messages = mutableListOf<String>()
        compose.setContent { MaterialTheme {
            GrupettoCalibrationPanel(state.value, { enabled ->
                if (!enabled || requireCalibrationMeter(ready.value, messages::add)) {
                    state.value = state.value.copy(recording = enabled)
                }
            }, {}, {}, {}, meterReady = ready.value)
        } }
        val record = compose.onNodeWithContentDescription("Record calibration")
        record.assertIsEnabled().performClick().assertIsOff()
        record.performClick().assertIsOff()
        compose.runOnIdle {
            assertEquals(listOf("Connect a power meter first", "Connect a power meter first"), messages)
            ready.value = true
        }
        record.performClick().assertIsOn()
        compose.runOnIdle { ready.value = false }
        record.performClick().assertIsOff()
        compose.runOnIdle { assertEquals(2, messages.size) }
    }

    @Test fun guidedCaptureButtonStillForwardsAttemptWithoutMeter() {
        var requested: Boolean? = null
        compose.setContent { MaterialTheme {
            CalibrationPanel(CalibrationUiState(), { requested = it }, {}, meterReady = false)
        } }
        compose.onNodeWithText("Start capture").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, requested) }
    }
}
