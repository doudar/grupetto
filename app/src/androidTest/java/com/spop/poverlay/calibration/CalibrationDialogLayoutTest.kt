package com.spop.poverlay.calibration

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.spop.poverlay.SettingsDialog
import com.spop.poverlay.ui.theme.Typography
import org.junit.Rule
import org.junit.Test

class CalibrationDialogLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val baseline = List(31) { 1000 + it * 100 }
    private val samples = (8..16 step 2).flatMap { x -> listOf(65, 80, 95).map { rpm ->
        CalibrationSample(baseline[x].toDouble(), rpm.toDouble(), 200.0, 220.0, (x * 100 + rpm).toLong())
    } }
    private val table = GrupettoCalibrationFitter.fit(baseline, samples)!!
    private fun noVerticalScrolling() {
        compose.onAllNodes(SemanticsMatcher("Requires vertical scrolling") {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { range -> range.maxValue() > 0 } == true
        }).assertCountEquals(0)
    }
    private fun visible(vararg labels: String) = labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }

    @Test fun tableDetailsBackupsAndSettingsFitWithoutScrolling() {
        compose.setContent { MaterialTheme(typography = Typography) {
            SettingsDialog("Calibrate using power pedals", {}, compact = true) {
                GrupettoCalibrationPanel(GrupettoCalibrationUiState(count = 235, table = table), {}, {}, {}, {},
                    meterReady = false, onManualEdit = {}, onCaptureSettings = {}, fileControls = {
                        CalibrationFileControls(true, true, { "{}" }, { error("Not used") })
                    })
            }
        } }
        visible("Record", "Activate", "Manual editor", "Capture settings", "Guided capture", "Done")
        noVerticalScrolling()
        compose.onNodeWithText("Fit details").performClick()
        visible("Clear table...", "Done")
        noVerticalScrolling()
        compose.onNodeWithText("Backups").performClick()
        visible("Save", "Load", "Share", "Done")
        noVerticalScrolling()
        compose.onNodeWithText("Table").performClick()
        compose.onNodeWithText("Capture settings").performClick()
        visible("Seconds", "Percent", "Restore defaults", "Cancel", "Save settings")
        noVerticalScrolling()
        compose.onNodeWithText("Seconds").performTextReplacement("5")
        visible("Save settings", "Cancel")
        noVerticalScrolling()
        compose.onNodeWithText("Save settings").performClick()
        visible("Manual editor", "Capture settings")
    }

    @Test fun guidedRideRecoveryAndReviewActionsFitWithoutScrolling() {
        val targets = CalibrationGuide(baseline, 2000).cells(samples)
        val state = mutableStateOf(CalibrationUiState(enabled = true, sampleCount = 235, baseline = baseline,
            targets = targets, target = targets.first(), recent = samples,
            frame = CalibrationFrame(1000, 2000, 80.0, 200.0, "bike", 1), pedalWatts = 220))
        compose.setContent { MaterialTheme(typography = Typography) {
            SettingsDialog("Calibrate using power pedals", {}, compact = true) {
                CalibrationPanel(state.value, {}, {}, onRest = { state.value = state.value.copy(resting = it) },
                    localCalibration = GrupettoCalibrationUiState(count = 235, table = table), meterReady = true)
            }
        } }
        visible("Pause capture", "Easier", "Harder", "Rest", "Skip", "Table view", "Done")
        noVerticalScrolling()
        compose.onNodeWithText("Rest").performClick()
        visible("Resume", "Table view", "Done")
        noVerticalScrolling()
        compose.onNodeWithText("Review").performClick()
        visible("Grupetto table controls", "Ride view", "Done")
        noVerticalScrolling()
    }
}
