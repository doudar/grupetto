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

    @Test fun zoneLabelsUseSupportedTextAndKeepEditableBoundaries() {
        var updated: List<Int?>? = null
        compose.setContent { MaterialTheme(typography = Typography) { Column {
            HeartRateZoneSettings(listOf(108, 126, 144, 162)) { updated = it }
        } } }
        listOf("1 to 2", "2 to 3", "3 to 4", "4 to 5").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("1 to 2").performTextReplacement("115")
        compose.runOnIdle { assertEquals(listOf(115, 126, 144, 162), updated) }
        compose.onNodeWithText("1 to 2").performTextClearance()
        compose.runOnIdle { assertEquals(listOf(null, 126, 144, 162), updated) }
    }

    @Test fun boundariesWrapIntoTwoRowsOnNarrowDialogsAndStayInOneRowWhenWide() {
        val width = mutableStateOf(280.dp)
        compose.setContent { MaterialTheme(typography = Typography) {
            Column(Modifier.requiredWidth(width.value)) {
                HeartRateZoneSettings(listOf(108, 126, 144, 162)) {}
            }
        } }
        fun bounds(label: String) = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        listOf("1 to 2", "2 to 3", "3 to 4", "4 to 5").forEach {
            compose.onNodeWithText(it).assertIsDisplayed().assertWidthIsAtLeast(130.dp)
        }
        assertEquals(bounds("1 to 2").top, bounds("2 to 3").top, 0f)
        assertEquals(bounds("3 to 4").top, bounds("4 to 5").top, 0f)
        assertTrue(bounds("3 to 4").top >= bounds("1 to 2").bottom)
        compose.runOnIdle { width.value = 560.dp }
        val firstTop = bounds("1 to 2").top
        listOf("2 to 3", "3 to 4", "4 to 5").forEach {
            assertEquals(firstTop, bounds(it).top, 0f)
        }
    }

}
