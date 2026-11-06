package com.spop.poverlay

import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class CalibrationSensorSummaryTest {
    private val compose = createComposeRule()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(compose).around(UiTestDiagnostics(compose))

    @Test fun onlyActiveCalibrationAndVisibleRecordingUseGreenAcrossStateChanges() {
        data class State(val active: Boolean, val recording: Boolean, val meterName: String?,
            val label: String, val color: Color)
        val green = Color(0xFF6EE7B7)
        val muted = Color(0xFF8796A8)
        val states = listOf(
            State(false, false, null, "Using built-in power", muted),
            State(true, false, null, "Using calibrated built-in power", green),
            State(true, true, null, "Using calibrated built-in power", green),
            State(false, true, null, "Using built-in power", muted),
            State(false, false, "Pedals", "Pedals", muted),
            State(false, true, "Pedals", "Pedals and calibrating built-in power", green),
            State(true, true, "Pedals", "Using calibrated built-in power and calibrating built-in power", green),
            State(true, false, "Pedals", "Using calibrated built-in power", green),
            State(false, false, "Pedals", "Pedals", muted),
            State(false, false, null, "Using built-in power", muted),
            State(true, true, "Pedals", "Using calibrated built-in power and calibrating built-in power", green))
        val current = mutableStateOf(states.first())
        compose.setContent {
            MaterialTheme {
                Surface(Modifier.width(340.dp), color = Color(0xFF1B2430)) {
                    CalibrationSensorSummary(current.value.active, current.value.recording,
                        current.value.meterName, "200 W", "External power active")
                }
            }
        }
        states.forEach { state ->
            compose.runOnIdle { current.value = state }
            assertTextColor(state.label, state.color)
            assertTextColor("Power meter", Color(0xFFBAC6D3))
            assertTextColor("200 W", Color(0xFFFBBF24))
            assertTextColor("External power active", Color(0xFFFBBF24))
        }
    }

    private fun assertTextColor(text: String, expected: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text).assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(text, expected, layouts.single().layoutInput.style.color)
    }
}
