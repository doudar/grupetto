package com.spop.poverlay.calibration

import android.content.Context
import androidx.compose.material.MaterialTheme
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

    @Test fun invalidValuesCannotSaveAndForgivingPresetCanBeCancelled() {
        var saved = false
        var closed = false
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), { saved = true }, { closed = true })
        } }
        compose.onNodeWithText("Cadence window (± rpm)").performScrollTo().performTextReplacement("NaN")
        compose.onNodeWithText("Save settings").assertIsNotEnabled()
        compose.onNodeWithText("More forgiving").performScrollTo().performClick()
        compose.onNodeWithText("Save settings").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(closed); assertFalse(saved) }
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
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {})
        } }
        compose.onNodeWithText("Settle time (seconds)").assertIsDisplayed()
        compose.onNodeWithText("Cadence window (± rpm)").assertIsDisplayed()
        compose.onNodeWithText("Resistance window (± % cell)").assertIsDisplayed()
        compose.onNodeWithText("Outlier tolerance (percentage points)").assertIsDisplayed()
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
