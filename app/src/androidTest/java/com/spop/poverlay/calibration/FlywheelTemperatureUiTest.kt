package com.spop.poverlay.calibration

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import android.graphics.Bitmap
import java.io.File

class FlywheelTemperatureUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun roomTemperatureDefaultsTo65AndSavesValidatedInput() {
        var room: Double? = null
        compose.setContent { MaterialTheme(colors = androidx.compose.material.darkColors(), typography = com.spop.poverlay.ui.theme.Typography) {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {}, onRoomTemperature = { room = it })
        } }
        compose.onNodeWithTag("room-temperature-input").assertTextContains("65")
        compose.onNodeWithTag("room-baseline-explanation").assertTextContains("keeping its rise above room", substring = true)
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("NaN")
        compose.onNodeWithText("Save settings").assertIsNotEnabled()
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("72")
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle { assertEquals(72.0, room) }
    }

    @Test fun cancelAndCapturePresetsLeaveRoomPreferenceAlone() {
        var room: Double? = null
        compose.setContent { MaterialTheme(colors = androidx.compose.material.darkColors(), typography = com.spop.poverlay.ui.theme.Typography) {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {}, roomFahrenheit = 70.0,
                onRoomTemperature = { room = it })
        } }
        compose.onNodeWithText("Stricter settings").performClick()
        compose.onNodeWithText("Defaults").performClick()
        compose.onNodeWithTag("room-temperature-input").assertTextContains("70")
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("80")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertNull(room) }
    }

    @Test fun estimateClearlyLabelsExperimentalAndMissingTelemetry() {
        compose.setContent { MaterialTheme(colors = androidx.compose.material.darkColors(), typography = com.spop.poverlay.ui.theme.Typography) { FlywheelTemperatureReadout(FlywheelTemperatureState()) } }
        compose.onNodeWithTag("flywheel-temperature-estimate").assertTextContains("Est. flywheel 65°F", substring = true)
            .assertTextContains("Experimental", substring = true).assertTextContains("cooling assumed", substring = true)
    }

    @Test fun checkpointPersistsRoomAndTemperatureWithoutTouchingCalibration() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = base.getSharedPreferences("thermal-test-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = FlywheelTemperatureTracker(context, firstScope)
            first.setRoomTemperature(72.0)
            val before = first.state.value.celsius
            firstScope.cancel()
            val second = FlywheelTemperatureTracker(context, secondScope)
            assertEquals(72.0, second.state.value.roomFahrenheit, 0.0)
            assertEquals(before, second.state.value.celsius, .1)
            assertFalse(second.state.value.receivingPower)
            assertEquals(setOf("checkpoint"), prefs.all.keys)
        } finally {
            firstScope.cancel(); secondScope.cancel(); prefs.edit().clear().commit()
        }
    }

    @Test fun baselineEditUpdatesLiveOutputButKeepsHistoricalPacketTemperatures() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = base.getSharedPreferences("thermal-baseline-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var now = 1000L
        try {
            val tracker = FlywheelTemperatureTracker(context,scope,{ now },{ 100000L+now })
            tracker.observe(CalibrationFrame(now,2000,80.0,200.0,"bike",1))
            val original = tracker.temperatureAt(now)!!
            tracker.outputTemperatureAt(now)
            tracker.setRoomTemperature(72.0)
            assertEquals(72.0,tracker.state.value.fahrenheit,1e-9)
            assertEquals(original,tracker.temperatureAt(now))
            val live = tracker.outputTemperatureAt(now)!!
            assertEquals(original.celsius+7/1.8,live.celsius,1e-9)
            assertEquals(72.0,live.roomFahrenheit,0.0)
            assertEquals(65.0,original.roomFahrenheit,0.0)
            now += 1000
            tracker.observe(CalibrationFrame(now,2000,80.0,200.0,"bike",1))
            assertEquals(72.0,tracker.temperatureAt(now)!!.roomFahrenheit,0.0)
            assertEquals(original,tracker.temperatureAt(1000))
            assertEquals(setOf("checkpoint"),prefs.all.keys)
        } finally { scope.cancel(); prefs.edit().clear().commit() }
    }

    @Test fun roomControlAndAllCaptureSlidersFitTabletViewport() {
        compose.setContent { MaterialTheme(colors = androidx.compose.material.darkColors(), typography = com.spop.poverlay.ui.theme.Typography) {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {}, onRoomTemperature = {})
        } }
        compose.onNodeWithTag("room-temperature-input").assertIsDisplayed()
        compose.onNodeWithTag("room-baseline-explanation").assertIsDisplayed()
        val viewport = compose.onNodeWithTag("capture-settings-body").getUnclippedBoundsInRoot()
        listOf("settle", "cadence", "resistance", "tolerance", "noise", "confirmation").forEach {
            val bounds = compose.onNodeWithTag("capture-hint-$it").getUnclippedBoundsInRoot()
            assertTrue("$it clipped at top", bounds.top >= viewport.top)
            assertTrue("$it clipped at bottom", bounds.bottom <= viewport.bottom)
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "temperature-settings.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
