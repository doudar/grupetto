package com.spop.poverlay

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import org.junit.Rule
import org.junit.Test

class SerialNameUpgradeDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun noticeExplainsRescanningAndDismisses() {
        val visible = mutableStateOf(true)
        compose.setContent {
            PTONOverlayTheme {
                if (visible.value) SerialNameUpgradeDialog { visible.value = false }
            }
        }
        compose.onNodeWithText("Check your Grupetto connection").assertIsDisplayed()
        compose.onNodeWithText("The Grupetto name may have changed after this upgrade. " +
            "If your training app or SmartSpin2k cannot find it, scan for the new name and pair again.")
            .assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithText("Check your Grupetto connection").assertDoesNotExist()
    }

    @Test fun backDismissesNotice() {
        val visible = mutableStateOf(true)
        compose.setContent {
            PTONOverlayTheme {
                if (visible.value) SerialNameUpgradeDialog { visible.value = false }
            }
        }
        compose.onNodeWithText("Check your Grupetto connection").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Check your Grupetto connection").assertDoesNotExist()
    }
}
