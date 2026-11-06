package com.spop.poverlay.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun FlywheelTemperatureReadout(state: FlywheelTemperatureState) {
    Text(String.format(Locale.US, "Est. flywheel %.0f°F · Experimental · %s", state.fahrenheit,
        if (state.receivingPower) "raw bike heat input" else "no live power; cooling assumed"),
        fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFF2BC75),
        modifier = Modifier.testTag("flywheel-temperature-estimate"))
}
