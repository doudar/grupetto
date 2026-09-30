package com.spop.poverlay

import android.graphics.Bitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class TrainerControlsTest {
    @get:Rule val compose = createComposeRule()
    private val control = BikeControl(true, { 1000L }) { error("UI tests must not drive a motor") }
    private fun setup() {
        control.acceptSample(BikeSample(150f, 80f, 40, 1000))
        compose.setContent { PTONOverlayTheme {
            val state = control.state.collectAsState().value
            SettingsDialog("Bike+ / CrossTrainer", {}) {
                TrainerControls(state, { control.localErg(it) }, { control.localSimulation(it) },
                    { control.localManual() }, { control.localResistance(it) }, control::tune)
            }
        } }
    }
    private fun setSlider(label: String, value: Float) {
        compose.onNodeWithContentDescription(label).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(value) }
    }
    private fun selected(label: String) {
        listOf("ERG", "Sim", "Manual").forEach {
            if (it == label) compose.onNodeWithText(it).assertIsSelected()
            else compose.onNodeWithText(it).assertIsNotSelected()
        }
    }
    private fun screenshot(name: String) {
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun modesShowMatchingControlsAndShiftsUpdateTheirLiveTargets() {
        setup()
        selected("Manual")
        compose.onNodeWithText("ERG").performClick()
        selected("ERG")
        compose.onNodeWithText("Gain control").assertIsDisplayed()
        compose.onNodeWithText("Resistance per shift").assertDoesNotExist()
        setSlider("ERG target", 200f); setSlider("Watts per shift", 15f)
        compose.runOnIdle { control.shift(1) }
        compose.onNodeWithText("215 W").assertIsDisplayed()
        screenshot("trainer-erg.png")
        compose.onNodeWithText("Sim").performClick()
        selected("Sim")
        compose.onNodeWithText("Watts per shift").assertDoesNotExist()
        setSlider("Incline", 5f); setSlider("Incline sensitivity", 3f)
        setSlider("Resistance per shift", 4f)
        compose.runOnIdle { control.shift(1) }
        assertEquals(4, control.state.value.shiftOffset)
        val incline = compose.onNodeWithContentDescription("Incline").fetchSemanticsNode().boundsInRoot
        val sensitivity = compose.onNodeWithText("Incline sensitivity").fetchSemanticsNode().boundsInRoot
        assertTrue(incline.bottom <= sensitivity.top)
        screenshot("trainer-sim.png")
        compose.onNodeWithText("Manual").performClick()
        selected("Manual")
        compose.onNodeWithText("Incline").assertDoesNotExist()
        setSlider("Resistance", 50f)
        compose.runOnIdle { control.shift(-1) }
        compose.onNodeWithText("46 points").assertIsDisplayed()
        screenshot("trainer-manual.png")
    }
    @Test fun remoteCommandsReplaceOpenDialogTargetsAndExternalControlFlag() {
        setup()
        for (client in listOf("ble:a", "dircon:b")) {
            val transport = if (client.startsWith("ble")) "Bluetooth" else "DirCon"
            compose.runOnIdle {
                assertEquals(1, control.procedure(client, byteArrayOf(0)).result)
                control.procedure(client, byteArrayOf(5, 225.toByte(), 0))
            }
            selected("ERG")
            compose.onNodeWithText("225 W").assertIsDisplayed()
            compose.onNodeWithText("External control · $transport").assertIsDisplayed()
            compose.onNodeWithText("Connected training apps automatically set the mode and target.").assertIsDisplayed()
            screenshot("trainer-external-${transport.lowercase()}.png")
            compose.onNodeWithText("Manual").performClick()
            compose.onNodeWithText("External control · $transport").assertDoesNotExist()
            compose.runOnIdle { control.procedure(client, byteArrayOf(0x11, 0, 0, 12, 254.toByte(), 40, 51)) }
            selected("Sim")
            compose.onNodeWithText("-5.0%").assertIsDisplayed()
            compose.runOnIdle { control.procedure(client, byteArrayOf(4, 38, 2)) }
            selected("Manual")
            compose.onNodeWithText("55 points").assertIsDisplayed()
            compose.runOnIdle { control.disconnect(client) }
            compose.onNodeWithText("External control · $transport").assertDoesNotExist()
        }
    }
}
