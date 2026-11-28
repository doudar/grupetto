package com.spop.poverlay.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.R
import com.spop.poverlay.overlay.composables.OverlayMinimizedContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class ExternalPowerOverlayTest {
    @get:Rule val compose = createComposeRule()
    @Test fun expandedCardShowsLiveNativeComparisonBelowExternalAndRemovesItOnFallback() {
        val comparison = mutableStateOf<String?>("Peloton 145 W")
        compose.setContent {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF101820)).padding(24.dp)) {
                StatCard(if (comparison.value != null) "External" else "Power", "210", "watts",
                    Modifier.width(StatCardWidth).height(124.dp).padding(bottom = 5.dp).testTag("power-card"), R.drawable.ic_power, "240", "25", "kJ",
                    secondaryValue = comparison.value)
            } }
        }
        compose.onNodeWithText("Peloton 145 W").assertIsDisplayed()
        val primary = compose.onNodeWithText("210", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val secondary = compose.onNodeWithText("Peloton 145 W", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(secondary.top >= primary.bottom)
        val card = compose.onNodeWithTag("power-card").fetchSemanticsNode().boundsInRoot
        val unit = compose.onNodeWithText("watts", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("All power rows fit within the expanded overlay", unit.bottom <= card.bottom)
        screenshot("external-power-card.png")
        compose.runOnIdle { comparison.value = "Peloton 160 W" }
        compose.onNodeWithText("Peloton 160 W").assertIsDisplayed()
        compose.runOnIdle { comparison.value = null }
        compose.onNodeWithText("Peloton 160 W").assertDoesNotExist()
        compose.onNodeWithText("External").assertDoesNotExist()
        compose.onNodeWithText("Power").assertIsDisplayed()
    }
    @Test fun minimizedComparisonFitsWithoutAttachedShifters() {
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().background(Color(0xFF101820)).padding(12.dp)) {
                OverlayMinimizedContent(
                    isMinimized = true,
                    showTimerWhenMinimized = true,
                    location = OverlayLocation.Top,
                    isTread = false,
                    powerLabel = "210",
                    cadenceLabel = "85",
                    speedLabel = "20.5",
                    resistanceLabel = "40",
                    inclineLabel = "0",
                    heartRateLabel = "125",
                    showPowerField = true,
                    showCadenceField = true,
                    showResistanceField = true,
                    showInclineField = false,
                    heartRateColor = Color.White,
                    contentAlpha = 1f,
                    timerLabel = "12:34",
                    timerPaused = false,
                    onTap = {},
                    onLongPress = {},
                    onOpenSettings = {},
                    onMinimizeToggle = {},
                    onLayout = {},
                    powerComparison = "Peloton 145 W",
                )
            }
        } }
        compose.onNodeWithText("Peloton 145 W").assertIsDisplayed()
        compose.onNodeWithContentDescription("Shift down: decrease resistance").assertDoesNotExist()
        compose.onNodeWithContentDescription("Shift up: increase resistance").assertDoesNotExist()
        val primary = compose.onNodeWithText("210").fetchSemanticsNode().boundsInRoot
        val secondary = compose.onNodeWithText("Peloton 145 W").fetchSemanticsNode().boundsInRoot
        assertTrue(secondary.top >= primary.bottom)
        screenshot("external-power-minimized.png")
    }
    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
