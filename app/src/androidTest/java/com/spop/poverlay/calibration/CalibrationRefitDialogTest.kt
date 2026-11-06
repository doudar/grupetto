package com.spop.poverlay.calibration

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CalibrationRefitDialogTest {
    @get:Rule val compose = createComposeRule()
    private val table = GrupettoCalibrationTable(List(31) { 1000 + it * 100 },
        List(496) { 0.0 }, List(496) { 1.0 }, 5, 1, 20.0, 10.0, true)
    private val preview = CalibrationRefitPreview(7L, CalibrationRefitResult(table,
        CalibrationRefitMetrics(20.0, -4.0, 24.0, 5),
        CalibrationRefitMetrics(10.0, -1.0, 15.0, 5), cellsAtBounds = 1, fittedCells = 1))

    @Test fun previewAndCancelNeverApply() {
        var prepares = 0; var applies = 0; var closes = 0
        compose.setContent { MaterialTheme {
            CalibrationRefitDialog(false, { prepares++; preview }, { applies++ }, {}, { closes++ })
        } }
        compose.onNodeWithText("Apply refit").assertIsEnabled()
        compose.onNodeWithText("5 retained observations and legacy effort aggregates").assertExists()
        compose.onNodeWithText("Mean absolute error: 20.0 to 10.0 W").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, prepares); assertEquals(0, applies); assertEquals(1, closes) }
    }

    @Test fun applyStaysOpenAndUndoUsesTheSameSnapshot() {
        var applies = 0; var undoes = 0; var closes = 0
        compose.setContent { MaterialTheme {
            CalibrationRefitDialog(false, { preview }, {
                assertSame(preview, it); applies++
            }, { assertSame(preview, it); undoes++ }, { closes++ })
        } }
        compose.onNodeWithText("Apply refit").performClick()
        compose.onNodeWithText("Undo refit").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, applies); assertEquals(0, closes) }
        compose.onNodeWithText("Undo refit").performClick()
        compose.onNodeWithText("Refit undone. The previous saved table was restored.").assertExists()
        compose.onNodeWithText("Apply refit").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, undoes); assertEquals(0, closes) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun staleApplyRequiresExplicitRebuild() {
        var prepares = 0; var applies = 0
        compose.setContent { MaterialTheme {
            CalibrationRefitDialog(false, { prepares++; preview }, {
                applies++; error("Table changed. Rebuild the preview.")
            }, {}, {})
        } }
        compose.onNodeWithText("Apply refit").performClick()
        compose.onNodeWithText("Table changed. Rebuild the preview.").assertExists()
        compose.onNodeWithText("Apply refit").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, prepares); assertEquals(1, applies) }
        compose.onNodeWithText("Rebuild preview").performScrollTo().performClick()
        compose.onNodeWithText("Apply refit").assertIsEnabled()
        compose.runOnIdle { assertEquals(2, prepares); assertEquals(1, applies) }
    }

    @Test fun failedPreviewShowsErrorAndCanBeRebuiltAfterPausing() {
        val recording = mutableStateOf(true)
        var prepares = 0
        compose.setContent { MaterialTheme {
            CalibrationRefitDialog(recording.value, {
                prepares++
                if (prepares == 1) error("No eligible saved efforts.")
                preview
            }, {}, {}, {})
        } }
        compose.onNodeWithText("Apply refit").assertIsNotEnabled()
        compose.onNodeWithText("Rebuild preview").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, prepares); recording.value = false }
        compose.onNodeWithText("Rebuild preview").performScrollTo().performClick()
        compose.onNodeWithText("No eligible saved efforts.").assertExists()
        compose.onNodeWithText("Rebuild preview").performScrollTo().performClick()
        compose.onNodeWithText("Apply refit").assertIsEnabled()
        compose.runOnIdle { recording.value = true }
        compose.onNodeWithText("Apply refit").assertIsNotEnabled()
    }

    @Test fun failedUndoDoesNotClaimRestorationOrClose() {
        var closes = 0
        compose.setContent { MaterialTheme {
            CalibrationRefitDialog(false, { preview }, {}, { error("Saved table changed; cannot undo.") }, { closes++ })
        } }
        compose.onNodeWithText("Apply refit").performClick()
        compose.onNodeWithText("Undo refit").performClick()
        compose.onNodeWithText("Saved table changed; cannot undo.").assertExists()
        compose.onNodeWithText("Refit undone. The previous saved table was restored.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, closes) }
    }

    @Test fun calculateActionIsVisibleAboveCorrectedErrorAndDisabledWhileRecording() {
        val recording = mutableStateOf(true)
        var opens = 0
        compose.setContent { MaterialTheme {
            GrupettoCalibrationPanel(GrupettoCalibrationUiState(recording = recording.value, table = table,
                errors = CalibrationErrorComparison(20.0, 10.0, 5, true)),
                {}, {}, {}, {}, onRefit = { opens++ })
        } }
        compose.onNodeWithText("Calculate New Solution").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { recording.value = false }
        val button = compose.onNodeWithText("Calculate New Solution").fetchSemanticsNode().boundsInRoot
        val watts = compose.onNodeWithText("10.0 W").fetchSemanticsNode().boundsInRoot
        assertTrue("Calculate button must sit above corrected watts", button.bottom <= watts.top)
        assertTrue("Calculate button must align with the right-side metric", button.right >= watts.left)
        compose.onNodeWithText("Calculate New Solution").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        compose.onNodeWithText("Calculate New Solution").assertIsDisplayed()
    }

    @Test fun calculateActionIsDisabledWithoutATable() {
        compose.setContent { MaterialTheme {
            GrupettoCalibrationPanel(GrupettoCalibrationUiState(), {}, {}, {}, {}, onRefit = {})
        } }
        compose.onNodeWithText("Calculate New Solution").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun savingDisablesRepeatApplyAndDismissUntilItFinishes() {
        val finish = CompletableDeferred<Unit>()
        var applies = 0; var closes = 0
        compose.setContent { MaterialTheme {
            CalibrationRefitDialog(false, { preview }, { applies++; finish.await() }, {}, { closes++ })
        } }
        compose.onNodeWithText("Apply refit").performClick()
        compose.onNodeWithText("Apply refit").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, applies); assertEquals(0, closes); finish.complete(Unit) }
        compose.onNodeWithText("Undo refit").assertIsEnabled()
        compose.onNodeWithText("Close").assertIsEnabled()
    }
}
