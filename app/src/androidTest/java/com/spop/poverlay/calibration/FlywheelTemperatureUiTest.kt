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

    @Test fun roomTemperatureDefaultsTo72AndSavesValidatedInput() {
        var room: Double? = null
        compose.setContent { MaterialTheme(colors = androidx.compose.material.darkColors(), typography = com.spop.poverlay.ui.theme.Typography) {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {}, onRoomTemperature = { room = it })
        } }
        compose.onNodeWithTag("room-temperature-input").assertTextContains("72")
        compose.onNodeWithTag("room-baseline-explanation").assertTextContains("keeping its rise above room", substring = true)
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("NaN")
        compose.onNodeWithText("Save settings").assertIsNotEnabled()
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("75")
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle { assertEquals(75.0, room) }
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

    @Test fun estimateKeepsTemperatureAndCellCountWithoutUncertaintyLine() {
        val state = androidx.compose.runtime.mutableStateOf(FlywheelTemperatureState())
        compose.setContent {
            MaterialTheme(colors = androidx.compose.material.darkColors(), typography = com.spop.poverlay.ui.theme.Typography) {
                FlywheelTemperatureReadout(state.value, thermalCells = 12)
            }
        }
        for (receiving in listOf(false, true, false)) {
            compose.runOnIdle { state.value = state.value.copy(receivingPower = receiving) }
            compose.onNodeWithTag("flywheel-temperature-estimate")
                .assertTextEquals("Est. flywheel 72°F · 12 thermal cells")
        }
    }

    @Test fun toggleConvertsTheUnsavedInputAndSavesTheSamePhysicalTemperature() {
        var savedRoom: Double? = null
        var selectedUnit = TemperatureUnit.Fahrenheit
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {},
                onRoomTemperature = { savedRoom = it }, onTemperatureUnit = { selectedUnit = it })
        } }
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("86")
        compose.onNodeWithTag("room-temperature-unit").performClick()
        compose.onNodeWithTag("room-temperature-input").assertTextContains("30")
        compose.onNodeWithText("°C").assertIsDisplayed()
        compose.runOnIdle { assertEquals(TemperatureUnit.Celsius, selectedUnit); assertNull(savedRoom) }
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle { assertEquals(86.0, savedRoom) }
    }

    @Test fun repeatedUnitTogglesDoNotRoundTheSavedDraft() {
        var savedRoom: Double? = null
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {},
                onRoomTemperature = { savedRoom = it })
        } }
        repeat(3) {
            compose.onNodeWithTag("room-temperature-unit").performClick()
            compose.onNodeWithTag("room-temperature-input").assertTextContains("22.22")
            compose.onNodeWithTag("room-temperature-unit").performClick()
            compose.onNodeWithTag("room-temperature-input").assertTextContains("72")
        }
        compose.onNodeWithTag("room-temperature-unit").performClick()
        compose.onNodeWithText("Save settings").performClick()
        compose.runOnIdle { assertEquals(72.0, savedRoom) }
    }

    @Test fun celsiusInputValidationUsesTheSamePhysicalLimits() {
        var savedRoom: Double? = null
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, {},
                onRoomTemperature = { savedRoom = it }, temperatureUnit = TemperatureUnit.Celsius)
        } }
        listOf("NaN", "", "-1", "44").forEach {
            compose.onNodeWithTag("room-temperature-input").performTextReplacement(it)
            compose.onNodeWithText("Save settings").assertIsNotEnabled()
        }
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("0")
        compose.onNodeWithText("Save settings").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(32.0, savedRoom) }
    }

    @Test fun invalidInputRemainsInvalidAfterSwitchAndCancelDoesNotSaveRoom() {
        var savedRoom: Double? = null
        var selectedUnit = TemperatureUnit.Fahrenheit
        var closed = false
        compose.setContent { MaterialTheme {
            CalibrationStreamSettingsDialog(CalibrationStreamSettings(), {}, { closed = true },
                onRoomTemperature = { savedRoom = it }, onTemperatureUnit = { selectedUnit = it })
        } }
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("NaN")
        compose.onNodeWithTag("room-temperature-unit").performClick()
        compose.onNodeWithTag("room-temperature-input").assertTextContains("NaN")
        compose.onNodeWithText("Save settings").assertIsNotEnabled()
        compose.onNodeWithTag("room-temperature-input").performTextReplacement("25")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertTrue(closed)
            assertNull(savedRoom)
            assertEquals(TemperatureUnit.Celsius, selectedUnit)
        }
    }

    @Test fun flywheelEstimateUsesTheSelectedUnitWithoutChangingItsValue() {
        val state = androidx.compose.runtime.mutableStateOf(FlywheelTemperatureState(celsius = 30.0))
        compose.setContent { MaterialTheme { FlywheelTemperatureReadout(state.value, 7) } }
        compose.onNodeWithTag("flywheel-temperature-estimate")
            .assertTextEquals("Est. flywheel 86°F · 7 thermal cells")
        compose.runOnIdle { state.value = state.value.copy(displayUnit = TemperatureUnit.Celsius) }
        compose.onNodeWithTag("flywheel-temperature-estimate")
            .assertTextEquals("Est. flywheel 30°C · 7 thermal cells")
        compose.runOnIdle { assertEquals(30.0, state.value.celsius, 0.0) }
    }

    @Test fun unitPreferencePersistsWithoutRetimestampingCheckpointOrPackets() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = base.getSharedPreferences("thermal-unit-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        firstScope.cancel()
        secondScope.cancel()
        try {
            val tracker = FlywheelTemperatureTracker(context, firstScope, { 1000L }, { 100000L })
            tracker.setRoomTemperature(65.0)
            tracker.observe(CalibrationFrame(1000, 2000, 80.0, 200.0, "bike", 1))
            val packet = tracker.temperatureAt(1000)
            val checkpoint = prefs.getString("checkpoint", null)
            val before = tracker.state.value
            tracker.setDisplayUnit(TemperatureUnit.Celsius)
            assertEquals(before.celsius, tracker.state.value.celsius, 0.0)
            assertEquals(before.roomFahrenheit, tracker.state.value.roomFahrenheit, 0.0)
            assertEquals(TemperatureUnit.Celsius, tracker.state.value.displayUnit)
            assertEquals(checkpoint, prefs.getString("checkpoint", null))
            assertEquals(packet, tracker.temperatureAt(1000))
            val restored = FlywheelTemperatureTracker(context, secondScope, { 1000L }, { 100000L })
            assertEquals(TemperatureUnit.Celsius, restored.state.value.displayUnit)
            assertEquals(65.0, restored.state.value.roomFahrenheit, 0.0)
        } finally {
            firstScope.cancel(); secondScope.cancel(); prefs.edit().clear().commit()
        }
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
            first.setRoomTemperature(65.0)
            val before = first.state.value.celsius
            firstScope.cancel()
            val second = FlywheelTemperatureTracker(context, secondScope)
            assertEquals(65.0, second.state.value.roomFahrenheit, 0.0)
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
            tracker.setRoomTemperature(80.0)
            assertEquals(80.0,tracker.state.value.fahrenheit,1e-9)
            assertEquals(original,tracker.temperatureAt(now))
            val live = tracker.outputTemperatureAt(now)!!
            assertEquals(original.celsius+8/1.8,live.celsius,1e-9)
            assertEquals(80.0,live.roomFahrenheit,0.0)
            assertEquals(72.0,original.roomFahrenheit,0.0)
            now += 1000
            tracker.observe(CalibrationFrame(now,2000,80.0,200.0,"bike",1))
            assertEquals(80.0,tracker.temperatureAt(now)!!.roomFahrenheit,0.0)
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
        compose.onNodeWithTag("room-temperature-unit").performClick()
        compose.onNodeWithTag("room-temperature-input").assertTextContains("22.22")
        compose.onNodeWithText("°C").assertIsDisplayed()
        val field = compose.onNodeWithTag("room-temperature-input").getUnclippedBoundsInRoot()
        val toggle = compose.onNodeWithTag("room-temperature-unit").getUnclippedBoundsInRoot()
        assertTrue(toggle.left >= field.left && toggle.right <= field.right)
        assertTrue(toggle.top >= field.top && toggle.bottom <= field.bottom)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "temperature-settings.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
}
