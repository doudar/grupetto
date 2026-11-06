package com.spop.poverlay.calibration

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class CalibrationTableHighlightTest {
    @get:Rule val compose = createComposeRule()
    private val baseline = List(31) { 1000 + it * 100 }
    private val frame = CalibrationFrame(1000, 2000, 80.0, 200.0, "bike", 1)
    private fun table(vararg filled: Int) = GrupettoCalibrationTable(baseline,
        List(496) { .1 }, List(496) { if (it in filled) 1.0 else 0.0 },
        filled.size, filled.size, 0.0, 0.0, true, recordedCoverage = filled.toSet(),
        cellSampleCounts = List(496) { if (it in filled) 1 else 0 })
    private fun chartContaining(description: String) = compose.onNodeWithContentDescription(description, substring = true)

    @Test fun pausedTableShowsCurrentAndFallbackAndRemovesFallbackWhenCurrentIsFilled() {
        val tableState = mutableStateOf(table(9 * 16 + 7))
        compose.setContent { MaterialTheme {
            GrupettoCoverageChart(tableState.value, CalibrationUiState(), recording = false,
                livePosition = CalibrationTablePosition(frame, baseline), now = 1000)
        } }
        chartContaining("current resistance cell 10, 80 rpm").assertIsDisplayed()
        chartContaining("green pulsing border: fallback resistance cell 9, 80 rpm").assertIsDisplayed()
        compose.runOnIdle { tableState.value = table(9 * 16 + 7, 10 * 16 + 7) }
        chartContaining("current resistance cell 10, 80 rpm").assertIsDisplayed()
        chartContaining("green pulsing border").assertDoesNotExist()
    }

    @Test fun emptyTableStillShowsCurrentCellAndStalePositionClearsIt() {
        val now = mutableStateOf(1000L)
        compose.setContent { MaterialTheme {
            GrupettoCoverageChart(null, CalibrationUiState(), recording = false,
                livePosition = CalibrationTablePosition(frame, baseline), now = now.value)
        } }
        chartContaining("current resistance cell 10, 80 rpm").assertIsDisplayed()
        chartContaining("green pulsing border").assertDoesNotExist()
        compose.runOnIdle { now.value = 3001 }
        chartContaining("current resistance cell").assertDoesNotExist()
    }
}
