package com.spop.poverlay.calibration

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

class CalibrationPanelTest {
    @get:Rule val compose = createComposeRule()
    @Test fun startPauseAndReviewCandidateWithExplicitClearConfirmation() {
        val state = mutableStateOf(CalibrationUiState(sampleCount = 18,
            status = "Waiting for fresh bike and power-meter readings."))
        var cleared = false
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.width(620.dp), color = Color(0xFF1B2430), contentColor = Color(0xFFE8EEF5)) {
                  Box(Modifier.padding(24.dp)) {
                    CalibrationPanel(state.value, { state.value = state.value.copy(enabled = it) }, { cleared = true }, meterReady = true)
                  }
                }
            }
        }
        compose.onNodeWithText("Start capture").performClick()
        compose.onNodeWithText("Pause capture").assertIsDisplayed()
        compose.onNodeWithText("Table view").assertExists()
        compose.onNodeWithText("Free ride").assertDoesNotExist()
        val baseline = List(31) { 1000 + it * 100 }
        compose.runOnIdle {
            state.value = state.value.copy(baseline = baseline, fit = CalibrationFit(
                baseline.toMutableList().apply { this[10] += 20 }, listOf(10), validationCount = 12,
                beforeRmse = 12.0, afterRmse = 4.0, ready = true,
                message = "Candidate ready for review. Sensor-board writing is not available."))
        }
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("Sensor-board fit · 1 / 29 points").assertIsDisplayed()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "calibration-panel.png")
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.onNodeWithText("Clear capture…").performScrollTo().performClick()
        assertFalse(cleared)
        compose.onNodeWithText("Keep data").performScrollTo().performClick()
        assertFalse(cleared)
        compose.onNodeWithText("Clear capture…").performClick()
        compose.onNodeWithText("Delete efforts").performScrollTo().performClick()
        assertTrue(cleared)
    }
}
