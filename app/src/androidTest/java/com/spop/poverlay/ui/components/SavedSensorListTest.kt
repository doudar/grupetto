package com.spop.poverlay.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import org.junit.Rule
import org.junit.Test

class SavedSensorListTest {
    @get:Rule val compose = createComposeRule()
    private val devices = (1..4).map { HeartRateDevice("AA:BB:CC:DD:EE:0$it", "Sensor $it") }

    @Test fun emptySavedListIsExplicit() {
        compose.setContent { MaterialTheme { SavedSensorList(emptyList(), null, null, {}, {}, {}) } }
        compose.onNodeWithText("No saved devices").assertIsDisplayed()
        compose.onNodeWithText("Connected").assertDoesNotExist()
    }

    @Test fun narrowSavedListScrollsAndPreventsCompetingConnections() {
        compose.setContent { MaterialTheme { Box(Modifier.width(280.dp)) {
            SavedSensorList(devices, devices[0].address, null, {}, {}, {})
        } } }
        compose.onNodeWithText("Connected").assertIsDisplayed()
        compose.onNodeWithText("Scroll down").assertIsDisplayed()
        compose.onNodeWithText("Sensor 4").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Connect").assertAll(isNotEnabled())
        compose.onNodeWithText("Scroll up").assertIsDisplayed()
    }

    @Test fun pendingConnectionHasReadableStatusAndBlocksOtherConnectButtons() {
        compose.setContent { MaterialTheme { SavedSensorList(devices, null, devices[0].address, {}, {}, {}) } }
        compose.onNodeWithText("Connecting").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsEnabled()
        compose.onNodeWithText("Sensor 2").performScrollTo()
        compose.onAllNodesWithText("Connect").assertAll(isNotEnabled())
    }
}
