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
internal fun FlywheelTemperatureReadout(state: FlywheelTemperatureState, thermalCells: Int = 0) {
    Text(String.format(Locale.US, "Est. flywheel %.0f°F · Experimental · %s · %d thermal cells", state.fahrenheit,
        if (!state.receivingPower) "no live power; cooling assumed" else
            if (state.confidence == TemperatureConfidence.InterruptedHistory) "history uncertain" else "ambient start assumed", thermalCells),
        fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFF2BC75),
        modifier = Modifier.testTag("flywheel-temperature-estimate"))
}
