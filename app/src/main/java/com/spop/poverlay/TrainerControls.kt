package com.spop.poverlay

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.control.ControlState
import java.util.Locale
import kotlin.math.roundToInt

/** Targets come directly from control state so remote commands also update an open dialog. */
@Composable
internal fun TrainerControls(
    state: ControlState,
    onErg: (Int) -> Unit,
    onSimulation: (Float) -> Unit,
    onManual: () -> Unit,
    onResistance: (Int) -> Unit,
    onTuning: (Int, Float, Int, Float) -> Unit
) {
    val mode = if (state.mode == ControlMode.Resistance) ControlMode.Manual else state.mode
    Column {
        state.externalControl?.let { ExternalControlFlag(it) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(ControlMode.Erg to "ERG", ControlMode.Simulation to "Sim", ControlMode.Manual to "Manual").forEach { (item, label) ->
                val active = mode == item
                Button({ when (item) {
                    ControlMode.Erg -> onErg(state.targetWatts)
                    ControlMode.Simulation -> onSimulation(state.targetIncline)
                    else -> onManual()
                } }, Modifier.weight(1f).semantics { selected = active },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (active) Color(0xFFB7F7DF) else Color(0xFF243242),
                        contentColor = if (active) Color(0xFF10251F) else Color(0xFFBAC6D3))) { Text(label) }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (mode) {
            ControlMode.Erg -> {
                TrainerSlider("ERG target", "${state.targetWatts} W", state.targetWatts.toFloat(), 25f..1000f) { onErg(it.roundToInt()) }
                TrainerSlider("Gain control", String.format(Locale.US, "%.3f", state.gain), state.gain, .001f..0.030f, 28) {
                    onTuning(state.shiftSize, it, state.wattsPerShift, state.inclineSensitivity)
                }
                TrainerSlider("Watts per shift", "${state.wattsPerShift} W", state.wattsPerShift.toFloat(), 1f..50f, 48) {
                    onTuning(state.shiftSize, state.gain, it.roundToInt(), state.inclineSensitivity)
                }
            }
            ControlMode.Simulation -> {
                TrainerSlider("Incline", String.format(Locale.US, "%.1f%%", state.targetIncline), state.targetIncline,
                    minOf(-20f, state.targetIncline)..maxOf(20f, state.targetIncline)) { onSimulation((it * 10).roundToInt() / 10f) }
                TrainerSlider("Incline sensitivity", String.format(Locale.US, "%.1f points / 1%%", state.inclineSensitivity),
                    state.inclineSensitivity, 0f..5f, 49) {
                    onTuning(state.shiftSize, state.gain, state.wattsPerShift, it)
                }
            }
            else -> TrainerSlider("Resistance", "${state.targetResistance} points", state.targetResistance.toFloat(), 0f..100f, 99) {
                onResistance(it.roundToInt())
            }
        }
        if (mode != ControlMode.Erg) TrainerSlider("Resistance per shift", "${state.shiftSize} points", state.shiftSize.toFloat(), 1f..10f, 8) {
            onTuning(it.roundToInt(), state.gain, state.wattsPerShift, state.inclineSensitivity)
        }
        Text("${state.message} · Pedal to apply resistance", fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFFB7F7DF))
        Text("Connected training apps automatically set the mode and target.", fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
    }
}

@Composable
internal fun ExternalControlFlag(transport: String) {
    Surface(color = Color(0xFFB7F7DF), shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)) {
        Text("External control · $transport", Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color(0xFF10251F), fontSize = 12.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun TrainerSlider(label: String, detail: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 15.sp, lineHeight = 20.sp)
        Text(detail, fontSize = 15.sp, lineHeight = 20.sp, color = Color(0xFFB7F7DF))
    }
    Slider(value, onChange, Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label }, valueRange = range, steps = steps)
}
