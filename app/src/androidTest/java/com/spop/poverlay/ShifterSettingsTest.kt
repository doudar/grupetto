package com.spop.poverlay

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ShifterSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewOnlyAppearsDuringPositionGestureEvenWhenCheckboxIsOff() {
        val shown = mutableStateOf(true)
        val inset = mutableStateOf(0f)
        val preview = mutableStateOf(false)
        val open = mutableStateOf(true)
        compose.setContent { PTONOverlayTheme {
            Surface(Modifier.fillMaxSize(), color = Color(0xFF101820)) {
                if (open.value) SettingsDialog("Bike+ / CrossTrainer · Shifters", { open.value = false }) {
                    ShifterSettings(shown.value, inset.value, { shown.value = it }, { inset.value = it }, { preview.value = it })
                }
                ShifterPositionPreview(preview.value, inset.value)
            }
        } }
        compose.onNodeWithText("Show shifters").assertIsOn().performClick().assertIsOff()
        assertNoShifters()
        val slider = compose.onNodeWithContentDescription("Shifter spacing")
        slider.performTouchInput { down(centerLeft); moveTo(center) }
        compose.onNodeWithContentDescription("Shift down: decrease resistance").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shift up: increase resistance").assertIsDisplayed()
        assertTrue(inset.value > .4f)
        val button = compose.onNodeWithContentDescription("Shift up: increase resistance").fetchSemanticsNode().boundsInRoot
        assertTrue(button.height > button.width)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.filesDir, "shifter-position-preview.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        slider.performTouchInput { up() }
        assertNoShifters()
        slider.performTouchInput { down(center); moveTo(centerRight) }
        compose.onNodeWithContentDescription("Shift up: increase resistance").assertIsDisplayed()
        slider.performTouchInput { cancel() }
        assertNoShifters()
        slider.performTouchInput { down(centerRight); moveTo(center) }
        compose.onNodeWithContentDescription("Shift up: increase resistance").assertIsDisplayed()
        compose.runOnIdle { open.value = false }
        assertNoShifters()
    }

    private fun assertNoShifters() {
        compose.onNodeWithContentDescription("Shift down: decrease resistance").assertDoesNotExist()
        compose.onNodeWithContentDescription("Shift up: increase resistance").assertDoesNotExist()
    }
}
