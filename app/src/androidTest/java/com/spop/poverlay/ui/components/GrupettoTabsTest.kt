package com.spop.poverlay.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class GrupettoTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun tabsExposeSelectionAndKeepTouchTargets() {
        val selected = mutableStateOf(0)
        compose.setContent { MaterialTheme(colors = darkColors()) {
            GrupettoTabs(selected.value, listOf("Discover", "Saved", "Zones"), { selected.value = it }, Modifier.width(450.dp))
        } }
        compose.onNodeWithText("Discover").assertIsSelected().assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Saved").assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("Discover").assertIsNotSelected()
        compose.onNodeWithText("Saved").assertIsSelected()
        compose.onNodeWithText("Zones").performClick().assertIsSelected()
        compose.onNodeWithText("Discover").performClick().assertIsSelected()
    }

    @Test fun narrowTabsCanScrollWithoutLosingSelection() {
        val selected = mutableStateOf(0)
        compose.setContent { MaterialTheme {
            GrupettoTabs(selected.value, listOf("Overview", "Services", "Notes"), { selected.value = it }, Modifier.width(210.dp))
        } }
        compose.onNodeWithText("Notes").performScrollTo().performClick().assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText("Overview").performScrollTo().performClick().assertIsSelected().assertIsDisplayed()
    }
}
