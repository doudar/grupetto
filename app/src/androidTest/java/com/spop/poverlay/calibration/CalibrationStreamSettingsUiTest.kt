package com.spop.poverlay.calibration

import android.content.Context
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap
import java.io.File

class CalibrationStreamSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsSaveEditedWindowsAndOutlierControlsWhileCancelIsSafe() {
        var saved: CalibrationStreamSettings? = null
        var closed = 0
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), { saved = it }, { closed++ })
        } }
        compose.onNodeWithText("Cadence window (± rpm)").performScrollTo().performTextReplacement("6")
        compose.onNodeWithText("Settle time (seconds)").performScrollTo().performTextReplacement("3")
        compose.onNodeWithText("Resistance window (± % cell)").performScrollTo().performTextReplacement("12")
        compose.onNodeWithText("Outlier tolerance (percentage points)").performScrollTo().performTextReplacement("7")
        compose.onNodeWithText("Recent noise allowance (×)").performScrollTo().performTextReplacement("5")
        compose.onNodeWithText("New level confirmation (readings)").performScrollTo().performTextReplacement("4")
        compose.onNodeWithText("Save settings").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(CalibrationStreamSettings(3, 6.0, 12, true, 7.0, 5.0, 4), saved)
            assertEquals(1, closed)
        }
    }

    @Test fun invalidValuesCannotSaveAndStricterPresetCanBeCancelled() {
        var saved = false
        var closed = false
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), { saved = true }, { closed = true })
        } }
        compose.onNodeWithText("Cadence window (± rpm)").performScrollTo().performTextReplacement("NaN")
        compose.onNodeWithText("Save settings").assertIsNotEnabled()
        compose.onNodeWithText("Stricter settings").performScrollTo().performClick()
        compose.onNodeWithText("Save settings").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(closed); assertFalse(saved) }
    }

    @Test fun forgivingDefaultAndStricterPresetSaveTheIntendedValues() {
        var saved: CalibrationStreamSettings? = null
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), { saved = it }, {})
        } }
        compose.onNodeWithText("Forgiving by default").assertIsDisplayed()
        compose.onNodeWithText("Stricter settings").performClick()
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle { assertEquals(CalibrationStreamSettings.Stricter, saved) }
        compose.onNodeWithText("Defaults").performScrollTo().performClick()
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle {
            assertEquals(10, saved!!.resistanceWindowPercent)
            assertEquals(6.0, saved!!.outlierTolerancePercent, 0.0)
        }
    }

    @Test fun toleranceExampleTracksTypedAndDraggedValuesAndDisabledSlidersStayDisabled() {
        var saved: CalibrationStreamSettings? = null
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), { saved = it }, {})
        } }
        compose.onNodeWithText("Outlier tolerance (percentage points)").performScrollTo().performTextReplacement("7.5")
        compose.onNodeWithTag("capture-hint-tolerance").assertTextEquals(outlierWattExample("7.5"))
        compose.onNodeWithTag("capture-slider-tolerance").performScrollTo().performTouchInput {
            swipe(Offset(width * .25f, centerY), Offset(width * .75f, centerY), 500)
        }
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle { assertTrue(saved!!.outlierTolerancePercent > 7.5) }
        compose.onNodeWithTag("capture-hint-tolerance").assertTextEquals(outlierWattExample(saved!!.outlierTolerancePercent.toString()))
        compose.onNodeWithTag("capture-slider-settle").performScrollTo().performTouchInput { click(Offset(width * .8f, centerY)) }
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle { assertTrue(saved!!.settleSeconds >= 10) }
        compose.onNodeWithContentDescription("Reject isolated outliers").performScrollTo().performClick()
        compose.onNodeWithTag("capture-slider-tolerance").assertIsNotEnabled()
        compose.onNodeWithTag("capture-slider-noise").assertIsNotEnabled()
        compose.onNodeWithTag("capture-slider-confirmation").assertIsNotEnabled()
        compose.onNodeWithTag("capture-slider-cadence").assertIsEnabled()
    }

    @Test fun savedSettingsSurviveReloadWithoutChangingCalibrationOrUnrelatedPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "stream-settings-test-${System.nanoTime()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            preferences.edit().putString("unrelated", "preserved").commit()
            val changed = CalibrationStreamSettings.MoreForgiving.copy(settleSeconds = 4, newLevelReadings = 5)
            val store = CalibrationStreamSettingsStore(preferences)
            store.save(changed)
            assertEquals(changed, CalibrationStreamSettingsStore(context.getSharedPreferences(name, Context.MODE_PRIVATE)).load())
            assertEquals("preserved", preferences.getString("unrelated", null))
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun settingsControlsAndSaveRemainAccessibleOnTablet() {
        compose.setContent { MaterialTheme(colors = darkColors()) {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {})
        } }
        compose.onNodeWithText("Settle time (seconds)").assertIsDisplayed()
        compose.onNodeWithText("Cadence window (± rpm)").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("capture-settings-body").getUnclippedBoundsInRoot()
        for (key in listOf("settle", "cadence", "resistance", "tolerance", "noise", "confirmation")) {
            for (part in listOf("slider", "hint")) {
                val control = compose.onNodeWithTag("capture-$part-$key").assertIsDisplayed()
                val bounds = control.getUnclippedBoundsInRoot()
                assertTrue("$part $key clipped above viewport", bounds.top >= viewport.top)
                assertTrue("$part $key clipped below viewport", bounds.bottom <= viewport.bottom)
            }
        }
        val initialScreenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "stream-capture-settings-top.png")
            .outputStream().use { initialScreenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        initialScreenshot.recycle()
        compose.onNodeWithText("Resistance window (± % cell)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Outlier tolerance (percentage points)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New level confirmation (readings)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save settings").assertIsDisplayed().assertIsEnabled()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir, "stream-capture-settings.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
