package com.spop.poverlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.spop.poverlay.ui.theme.Typography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HeartRateZoneSettingsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun zoneBoxesAreLabelledAndKeypadEditsBoundaries() {
        var updated: List<Int?>? = null
        compose.setContent { MaterialTheme(typography = Typography) { Column {
            HeartRateZoneSettings(listOf(108, 126, 144, 162, 180)) { updated = it }
        } } }
        listOf("Zone 1", "Zone 2", "Zone 3", "Zone 4", "Zone 5").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        // The keypad only appears once a box is picked; Del removes one digit at a time. Keys are
        // found by tag because a box showing "1" would also match the text "1".
        fun key(k: String) = compose.onNodeWithTag("zoneKey_$k").performClick()
        compose.onNodeWithText("Zone 1").performClick()
        repeat(3) { key("Del") }
        compose.runOnIdle { assertEquals(listOf(null, 126, 144, 162, 180), updated) }
        listOf("1", "1", "5").forEach { key(it) }
        compose.runOnIdle { assertEquals(listOf(115, 126, 144, 162, 180), updated) }
        key("Del")
        compose.runOnIdle { assertEquals(listOf(11, 126, 144, 162, 180), updated) }
    }

    @Test fun boxesWrapIntoTwoRowsOnNarrowDialogsAndStayInOneRowWhenWide() {
        val width = mutableStateOf(360.dp)
        compose.setContent { MaterialTheme(typography = Typography) {
            Column(Modifier.requiredWidth(width.value)) {
                HeartRateZoneSettings(listOf(108, 126, 144, 162, 180)) {}
            }
        } }
        fun bounds(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        listOf("Zone 1", "Zone 2", "Zone 3", "Zone 4", "Zone 5").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertWidthIsAtLeast(80.dp)
        }
        assertEquals(bounds("Zone 1").top, bounds("Zone 3").top, 0f)
        assertEquals(bounds("Zone 4").top, bounds("Zone 5").top, 0f)
        assertTrue(bounds("Zone 4").top >= bounds("Zone 1").bottom)
        compose.runOnIdle { width.value = 700.dp }
        val firstTop = bounds("Zone 1").top
        listOf("Zone 2", "Zone 3", "Zone 4", "Zone 5").forEach {
            assertEquals(firstTop, bounds(it).top, 0f)
        }
    }

}
