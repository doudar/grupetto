package com.spop.poverlay.calibration

import android.graphics.Bitmap
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CalibrationManualEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun table(): GrupettoCalibrationTable {
        val baseline = List(31) { 1000 + it * 100 }
        val samples = (8..16 step 2).flatMap { x -> listOf(65, 80, 95).map { rpm ->
            CalibrationSample(baseline[x].toDouble(), rpm.toDouble(), 200.0, 220.0, (x * 100 + rpm).toLong())
        } }
        return GrupettoCalibrationFitter.fit(baseline, samples)!!
    }

    @Test fun signedInputValidationBlendingUndoRedoAndExplicitSave() {
        val original = table()
        var saved: GrupettoCalibrationTable? = null
        var closed = false
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(original, false, { expected, edited -> assertEquals(original, expected); saved = edited }, { closed = true })
        } }
        compose.onNodeWithText("Save draft").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Point 15, 80 rpm,", substring = true).performClick()
        compose.onNodeWithText("Correction (%)").performScrollTo().performTextReplacement("-99")
        compose.onNodeWithText("Set correction").assertIsNotEnabled()
        compose.onNodeWithText("Correction (%)").performTextReplacement("-12.5")
        compose.onNodeWithText("Set correction").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Point 15, 80 rpm, -12.5%, manual").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Save draft").assertIsNotEnabled()
        compose.onNodeWithText("Redo").performClick()
        compose.onNodeWithText("Compare original").performClick()
        compose.onNodeWithText("Show edited").assertExists().performClick()
        compose.onNodeWithText("Save draft").performClick()
        compose.waitForIdle()
        assertTrue(closed)
        assertEquals(-12.5, CalibrationManualEdit.percent(saved!!.gains[15 * 16 + 7]), 1e-9)
        assertEquals(original.sampleCount, saved!!.sampleCount)
    }

    @Test fun toolsAndDiscardStayLocalUntilSaveAndCaptureLayout() {
        var saves = 0
        var closed = false
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(table(), true, { _, _ -> saves++ }, { closed = true })
        } }
        compose.onNodeWithText("Fill all gaps").performScrollTo().performClick()
        compose.onNodeWithText("Fill all gaps").assertIsNotEnabled()
        compose.onNodeWithText("Restore recorded fit").performScrollTo().performClick()
        compose.onNodeWithText("Fill all gaps").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("80 rpm · point 15").performScrollTo()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.filesDir, "manual-editor.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("Fit table").assertExists().performClick()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Keep editing").performClick()
        assertFalse(closed)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Discard edits").performClick()
        assertTrue(closed)
        assertEquals(0, saves)
    }

    @Test fun retainedSessionKeepsEditsAndUndoAcrossRecreation() {
        val original = table()
        val history = CalibrationEditHistory(original)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(original, false, { _, _ -> }, {}, history)
        } }
        compose.onNodeWithText("Correction (%)").performScrollTo().performTextReplacement("-8")
        compose.onNodeWithText("Set correction").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Point 15, 80 rpm, -8.0%, manual").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Save draft").assertIsNotEnabled()
    }
}
