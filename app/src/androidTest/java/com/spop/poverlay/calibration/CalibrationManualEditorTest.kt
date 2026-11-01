package com.spop.poverlay.calibration

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalibrationManualEditorTest {
    @get:Rule val compose = createComposeRule()
    private fun clickAfterKeyboardLayoutSettles(label: String) {
        var lastBounds: androidx.compose.ui.geometry.Rect? = null
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(5000) {
            val bounds = compose.onNodeWithText(label).fetchSemanticsNode().boundsInWindow
            val now = android.os.SystemClock.uptimeMillis()
            if (bounds != lastBounds) { lastBounds = bounds; stableSince = now }
            now - stableSince >= 300
        }
        try { compose.onNodeWithText(label).assertIsDisplayed().performClick() }
        catch (error: AssertionError) {
            throw AssertionError("Action ${compose.onNodeWithText(label).fetchSemanticsNode().boundsInWindow}; " +
                "editor ${compose.onNodeWithTag("power-table-studio").fetchSemanticsNode().boundsInWindow}; " +
                "toolbar present ${compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty()}", error)
        }
    }
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
        compose.onNodeWithText("Correction (%)").assertIsDisplayed().performTextReplacement("-99")
        compose.onNodeWithText("Set correction").assertIsNotEnabled()
        compose.onNodeWithText("Correction (%)").performTextReplacement("-12.5")
        clickAfterKeyboardLayoutSettles("Set correction")
        compose.onNodeWithText("Set 1 selected cell", substring = true).assertExists()
        compose.onNodeWithText("Correction (%)").assertIsNotFocused()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun quickFixesAreVisibleWithoutScrollingAndDiscardStaysLocal() {
        var saves = 0
        var closed = false
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(table(), true, { _, _ -> saves++ }, { closed = true })
        } }
        listOf("Fill all gaps", "Smooth whole table", "Reduce all 20%", "Restore recorded fit").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Fill all gaps").performClick()
        compose.onNodeWithText("Fill all gaps").assertIsNotEnabled()
        compose.onNodeWithText("Restore recorded fit").performClick()
        compose.onNodeWithText("Fill all gaps").assertIsEnabled()
        compose.onNodeWithText("Undo").performClick()
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

    @Test fun quickFixesAffectWholeTableSupportUndoAndRespectCompareMode() {
        val original = table()
        val history = CalibrationEditHistory(original)
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(original, false, { _, _ -> }, {}, history)
        } }
        compose.onNodeWithText("Fill all gaps").performClick()
        compose.runOnIdle { assertEquals(496, history.current.correctionPercent.count { it != null }) }
        val filled = history.current
        compose.onNodeWithText("Smooth whole table").performClick()
        compose.runOnIdle { assertEquals(CalibrationManualEdit.smooth(filled, (0 until 496).toSet()), history.current) }
        compose.onNodeWithText("Undo").performClick()
        compose.runOnIdle { assertEquals(filled, history.current) }
        compose.onNodeWithText("Reduce all 20%").performClick()
        compose.runOnIdle {
            filled.gains.indices.forEach { index -> assertEquals(CalibrationManualEdit.percent(filled.gains[index]) * .8,
                CalibrationManualEdit.percent(history.current.gains[index]), 1e-9) }
        }
        compose.onNodeWithText("Compare original").performClick()
        listOf("Smooth whole table", "Reduce all 20%", "Restore recorded fit").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("Show edited").performClick()
        compose.onNodeWithText("Restore recorded fit").performClick()
        compose.runOnIdle {
            original.gains.indices.forEach { assertEquals(original.gains[it], history.current.gains[it], 1e-12) }
            assertTrue(history.current.manualCoverage.isEmpty())
        }
    }

    @Test fun retainedSessionKeepsEditsAndUndoAcrossRecreation() {
        val original = table()
        val history = CalibrationEditHistory(original)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(original, false, { _, _ -> }, {}, history)
        } }
        compose.onNodeWithText("Correction (%)").assertIsDisplayed().performTextReplacement("-8")
        clickAfterKeyboardLayoutSettles("Set correction")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("Point 15, 80 rpm, -8.0%, manual").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.onNodeWithText("Save draft").assertIsNotEnabled()
    }

    @Test fun inspectorPagesExposeEveryActionWithoutScrolling() {
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(table(), false, { _, _ -> }, {})
        } }
        listOf("Correction (%)", "Set correction", "-1%", "+1%").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Scope").performClick()
        listOf("Cell", "Cadence row", "Resistance column", "Whole table", "Blend into neighbors").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Tools").performClick()
        listOf("Smooth selection", "Reduce corrections 20%", "Revert this editing session").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test fun fullscreenFitShowsAllCornersAndCadenceLabelsIncludingAfterZooming() {
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(table(), false, { _, _ -> }, {})
        } }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val display = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(display)
        try { compose.waitUntil(5000) {
            val bounds = compose.onNodeWithTag("power-table-studio").fetchSemanticsNode().boundsInWindow
            bounds.width >= display.widthPixels - 1 && bounds.height >= display.heightPixels - 1
        } } catch (error: Throwable) {
            throw AssertionError("Editor bounds ${compose.onNodeWithTag("power-table-studio").fetchSemanticsNode().boundsInWindow}; display ${display.widthPixels} x ${display.heightPixels}", error)
        }
        fun assertFits() {
            val viewport = compose.onNodeWithTag("calibration-grid-viewport").getUnclippedBoundsInRoot()
            for (x in listOf(0, 30)) for (rpm in listOf(45, 120)) {
                val cell = compose.onNodeWithContentDescription("Point $x, $rpm rpm,", substring = true)
                cell.assertIsDisplayed()
                val bounds = cell.getUnclippedBoundsInRoot()
                assertTrue("Cell $x/$rpm extends outside the viewport", bounds.left >= viewport.left &&
                    bounds.top >= viewport.top && bounds.right <= viewport.right && bounds.bottom <= viewport.bottom)
            }
            for (rpm in 45..120 step 5) {
                val label = compose.onNodeWithText(rpm.toString())
                label.assertIsDisplayed()
                val bounds = label.getUnclippedBoundsInRoot()
                assertTrue("Cadence label $rpm is clipped", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            }
        }
        assertFits()
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithContentDescription("Point 0, 120 rpm,", substring = true).performTouchInput { swipeUp() }
        compose.onNodeWithText("Fit table").performClick()
        assertFits()
    }
}
