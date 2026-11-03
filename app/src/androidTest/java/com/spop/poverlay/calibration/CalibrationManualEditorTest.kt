package com.spop.poverlay.calibration

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import com.spop.poverlay.UiTestDiagnostics

class CalibrationManualEditorTest {
    private val compose = createComposeRule()
    private val failureDiagnostics = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            // Capture before Compose disposes the dialog. Diagnostic failures must
            // never hide the original assertion or change the behavior under test.
            runCatching { captureFailure(description, error) }.onFailure {
                android.util.Log.e("ManualEditorTest", "Could not save failure diagnostics", it)
            }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(compose).around(UiTestDiagnostics(compose)).around(failureDiagnostics)

    private fun captureFailure(description: Description, failure: Throwable) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "calibration-test-diagnostics")
        check(directory.isDirectory || directory.mkdirs())
        val name = description.methodName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val report = StringBuilder().appendLine(description.displayName).appendLine(failure.stackTraceToString())
        fun section(title: String, capture: () -> String) {
            report.appendLine("\n=== $title ===")
            report.appendLine(runCatching(capture).getOrElse { "Capture failed: ${it.stackTraceToString()}" })
        }
        // This is the actual device screen, including dialog clipping/system bars,
        // rather than a rendering of only the Compose root's nominal bounds.
        section("Device screenshot") {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(directory, "$name.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                "${bitmap.width} x ${bitmap.height} pixels"
            } finally { bitmap.recycle() }
        }
        section("Display and configuration") {
            val display = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(display)
            "Real display: $display\nResources: ${context.resources.displayMetrics}\n" +
                "Configuration: ${context.resources.configuration}"
        }
        section("Native window and view geometry") {
            var details = "WindowInspector requires API 29 or later."
            if (Build.VERSION.SDK_INT >= 29) instrumentation.runOnMainSync {
                details = buildString {
                    WindowInspector.getGlobalWindowViews().take(10).forEachIndexed { index, root ->
                        appendLine("Window $index: ${root.layoutParams}")
                        val insets = ViewCompat.getRootWindowInsets(root)
                        appendLine("System bars: ${insets?.getInsets(WindowInsetsCompat.Type.systemBars())}; " +
                            "IME: ${insets?.getInsets(WindowInsetsCompat.Type.ime())}; " +
                            "IME visible: ${insets?.isVisible(WindowInsetsCompat.Type.ime())}")
                        var remaining = 100
                        fun visit(view: View, depth: Int) {
                            if (remaining-- <= 0 || depth > 12) return
                            val location = IntArray(2).also(view::getLocationOnScreen)
                            val visible = Rect()
                            val isVisible = view.getGlobalVisibleRect(visible)
                            val frame = Rect().also(view::getWindowVisibleDisplayFrame)
                            appendLine("${"  ".repeat(depth)}${view.javaClass.name}: " +
                                "size=${view.width}x${view.height}, measured=${view.measuredWidth}x${view.measuredHeight}, " +
                                "screen=${location.contentToString()}, shown=${view.isShown}, " +
                                "globalVisible=$isVisible/$visible, windowFrame=$frame, layout=${view.layoutParams}")
                            if (view is ViewGroup) for (child in 0 until view.childCount) visit(view.getChildAt(child), depth + 1)
                        }
                        visit(root, 0)
                    }
                }
            }
            details
        }
        for (unmerged in listOf(false, true)) section("Semantics (unmerged=$unmerged)") {
            compose.onAllNodes(isRoot(), useUnmergedTree = unmerged).printToString(maxDepth = 40).take(500_000)
        }
        section("Important bounds in window and root") {
            val matcher = hasTestTag("power-table-studio") or hasTestTag("calibration-grid-viewport") or
                hasText("Correction (%)") or hasText("Fill all gaps") or hasText("Set correction") or
                hasContentDescription("Point 0, 45 rpm,", substring = true) or
                hasContentDescription("Point 30, 120 rpm,", substring = true)
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().joinToString("\n") {
                "Node ${it.id}: window=${it.boundsInWindow}, root=${it.boundsInRoot}, size=${it.size}; ${it.config}"
            }
        }
        File(directory, "$name.txt").writeText(report.toString())
    }

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

    @Test fun signedInputValidationExactCellsUndoRedoAndExplicitSave() {
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
        original.gains.indices.filter { it != 15 * 16 + 7 }.forEach {
            assertEquals("Unselected cell $it must stay unchanged", original.gains[it], saved!!.gains[it], 0.0)
        }
    }

    @Test fun quickFixesAreVisibleWithoutScrollingAndDiscardStaysLocal() {
        var saves = 0
        var closed = false
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationManualEditor(table(), true, { _, _ -> saves++ }, { closed = true })
        } }
        listOf("Fill all gaps", "Smooth whole table", "Reduce all 20%", "Reset corrections").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Fill all gaps").performClick()
        compose.onNodeWithText("Fill all gaps").assertIsNotEnabled()
        compose.onNodeWithText("Reset corrections").performClick()
        compose.onNodeWithText("Fill all gaps").assertIsNotEnabled()
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
        listOf("Smooth whole table", "Reduce all 20%", "Reset corrections").forEach {
            compose.onNodeWithText(it).assertIsNotEnabled()
        }
        compose.onNodeWithText("Show edited").performClick()
        compose.onNodeWithText("Reset corrections").performClick()
        compose.runOnIdle {
            assertTrue(history.current.gains.all { it == 0.0 })
            assertEquals(496, history.current.correctionPercent.count { it != null })
            assertTrue(history.current.ready)
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
        compose.onNodeWithText("Blend into neighbors").assertDoesNotExist()
        listOf("Cell", "Cadence row", "Resistance column", "Whole table").forEach {
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
