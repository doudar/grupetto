package com.spop.poverlay.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class CalibrationCaptureErrorUiTest {
    @get:Rule val compose = createComposeRule()
    private val mismatch = "Bike, power meter or calibration changed. Clear the saved capture to start a new one."
    private val capture = mutableStateOf(CalibrationUiState())
    private val tableView = mutableStateOf(true)

    private fun show() {
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.width(620.dp).height(440.dp), color = Color(0xFF1B2430),
                    contentColor = Color(0xFFE8EEF5)) {
                    if (tableView.value) Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                        GrupettoCalibrationPanel(GrupettoCalibrationUiState(count = capture.value.sampleCount,
                            message = "Recording paused"), {}, {}, {}, {}, meterReady = true, capture = capture.value)
                    } else Box(Modifier.padding(12.dp)) {
                        CalibrationPanel(capture.value, {}, {}, meterReady = true)
                    }
                }
            }
        }
    }

    private fun assertErrorInBothViews(samples: Int) {
        capture.value = CalibrationUiState(sampleCount = samples).captureFailed(mismatch)
        show()
        for (table in listOf(true, false)) {
            compose.runOnIdle { tableView.value = table }
            compose.onNodeWithText(mismatch).assertIsDisplayed()
            compose.onNodeWithText("Paused").assertDoesNotExist()
            compose.onNodeWithText("Ready when you are").assertDoesNotExist()
            compose.onNodeWithText("Recording paused").assertDoesNotExist()
        }
    }

    @Test fun zeroSampleMismatchIsVisibleInBothViews() = assertErrorInBothViews(0)
    @Test fun savedSampleMismatchIsVisibleInBothViews() = assertErrorInBothViews(235)

    @Test fun ordinaryPauseKeepsNormalFeedback() {
        capture.value = CalibrationUiState(status = "Capture paused")
        show()
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNodeWithText(mismatch).assertDoesNotExist()
        compose.runOnIdle { tableView.value = false }
        compose.onNodeWithText("Ready when you are").assertIsDisplayed()
        compose.onNodeWithText(mismatch).assertDoesNotExist()
    }

    @Test fun errorDisappearsWhenResolvedOrCaptureCleared() {
        capture.value = CalibrationUiState().captureFailed(mismatch)
        show()
        for (table in listOf(true, false)) {
            compose.runOnIdle {
                tableView.value = table
                capture.value = CalibrationUiState().captureFailed(mismatch)
            }
            compose.onNodeWithText(mismatch).assertIsDisplayed()
            compose.runOnIdle { capture.value = capture.value.captureIdentityVerified() }
            compose.onNodeWithText(mismatch).assertDoesNotExist()
            compose.runOnIdle { capture.value = capture.value.captureFailed(mismatch) }
            compose.onNodeWithText(mismatch).assertIsDisplayed()
            compose.runOnIdle {
                capture.value = CalibrationUiState(status = "Saved capture cleared. Bike calibration is unchanged.")
            }
            compose.onNodeWithText(mismatch).assertDoesNotExist()
        }
    }
}
