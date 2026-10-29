package com.spop.poverlay.calibration

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GrupettoCalibrationPanel(state: GrupettoCalibrationUiState, onRecord: (Boolean) -> Unit,
    onActivate: (Boolean) -> Unit, onClear: () -> Unit, onGuide: () -> Unit,
    meterReady: Boolean = false, capture: CalibrationUiState = CalibrationUiState(),
    metrics: CalibrationLiveMetrics = CalibrationLiveMetrics(),
    fileControls: @Composable () -> Unit = {}) {
    var confirmClear by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Grupetto table", fontSize = 18.sp, lineHeight = 22.sp)
                Text("${state.count} saved", fontSize = 14.sp, lineHeight = 18.sp, color = Color(0xFF6EE7B7))
            }
            Switch(state.recording, onRecord, enabled = state.recording || meterReady)
            Text("Record", fontSize = 16.sp)
            Spacer(Modifier.width(16.dp))
            Switch(state.active, onActivate, enabled = state.active || state.canActivate)
            Text("Activate", fontSize = 16.sp)
        }
        CaptureErrorNotice(capture)
        if (!meterReady) Text("Connect a power meter to record", fontSize = 14.sp, lineHeight = 18.sp, color = Color(0xFFFBBF24))
        CalibrationMetrics(metrics, showCorrected = true)
        GrupettoCoverageChart(state.displayTable ?: state.table, capture, state.recording && meterReady)
        CaptureHoldFeedback(capture, state.recording)
        CalibrationCorrectionLegend()
        CalibrationErrorSummary(state.errors)
        fileControls()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onGuide, Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Guided capture") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.active && !state.verified -> "Active · waiting for bike"
                state.active -> "Active: ${state.activeCount} efforts" + if (state.recording) " · auto-update on" else ""
                state.table?.ready == true -> "Draft ready to activate"
                state.savedCount > 0 -> "Saved ${state.savedCount}-effort table ready to activate"
                else -> "More ride coverage needed"
            }, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF6EE7B7))
            TextButton({ details = !details }) { Text(if (details) "Hide details" else "Fit details") }
            if (!confirmClear) TextButton({ confirmClear = true }) { Text("Clear table…") }
        }
        if (details) {
            if (capture.error == null) Text(state.message, fontSize = 14.sp, lineHeight = 18.sp)
            Text("Colors show the ${if (state.active) "active" else "preview"} table's correction as a percentage of raw bike watts. " +
                "Red: bike overestimated. Blue: bike underestimated. Scale saturates at -10% / +10%.",
                fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
            state.errors?.let {
                Text("Average absolute error on the same ${it.count} saved efforts. After uses " +
                    (if (it.preview) "the preview table." else "the active table.") + " Validation below uses held-out data.",
                    fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
            }
            state.table?.let { table ->
                Text(String.format(Locale.US, "Draft validation RMSE %.2f to %.2f W", table.beforeRmse, table.afterRmse), fontSize = 14.sp)
                if (!table.ready) Text("Draft needs more resistances and cadences", fontSize = 12.sp, color = Color(0xFFFBBF24))
                if (state.active && !state.recording && table.ready) TextButton({ onActivate(true) }) { Text("Update active table from latest fit") }
            }
            Text("Record + Activate: every saved effort updates the table. Error scores are informational. Pulse: orange settles, yellow holds, green saves.",
                fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
        }
        if (confirmClear) {
            Text("Clear Grupetto data and turn correction off?", fontSize = 14.sp)
            Row { TextButton({ confirmClear = false; onClear() }) { Text("Clear Grupetto table") }
                TextButton({ confirmClear = false }) { Text("Keep table") } }
        }
    }
}

@Composable
private fun CalibrationCorrectionLegend() {
    Column {
        Canvas(Modifier.fillMaxWidth().height(7.dp)) {
            drawRect(Brush.horizontalGradient(listOf(CorrectionOverColor, CorrectionZeroColor, CorrectionUnderColor)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-10% · bike high", fontSize = 11.sp, lineHeight = 15.sp)
            Text("0%", fontSize = 11.sp, lineHeight = 15.sp)
            Text("+10% · bike low", fontSize = 11.sp, lineHeight = 15.sp)
        }
        Text("Correction · gray: no nearby data", fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF9EAEC0))
    }
}

@Composable
private fun CalibrationErrorSummary(errors: CalibrationErrorComparison?) {
    fun watts(value: Double?) = value?.let { String.format(Locale.US, "%.1f W", it) } ?: "-"
    Column {
        Text("Average error vs pedals" + when {
            errors?.afterWatts == null -> ""
            errors.preview -> " · preview"
            else -> " · active table"
        }, fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Before calibration", fontSize = 14.sp, lineHeight = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(watts(errors?.beforeWatts), fontSize = 24.sp, lineHeight = 28.sp)
            }
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text("After calibration", fontSize = 14.sp, lineHeight = 18.sp)
                Spacer(Modifier.weight(1f))
                val improved = errors?.afterWatts?.let { it < errors.beforeWatts }
                Text(watts(errors?.afterWatts), fontSize = 24.sp, lineHeight = 28.sp,
                    color = when (improved) { true -> Color(0xFF6EE7B7); false -> Color(0xFFFBBF24); null -> Color(0xFF9EAEC0) })
            }
        }
    }
}

@Composable
private fun GrupettoCoverageChart(table: GrupettoCalibrationTable?, capture: CalibrationUiState, recording: Boolean) {
    val cell = recordingCell(capture, recording)
    val pulse = if (cell != null) {
        val transition = rememberInfiniteTransition(label = "Recording cell")
        transition.animateFloat(.25f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "Recording pulse").value
    } else 0f
    val progressColor = captureProgressColor(capture.progress)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("120\nrpm\n\n45", fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.width(32.dp))
        Canvas(Modifier.weight(1f).height(80.dp).semantics {
            contentDescription = "Correction percentage: red -10% for bike overestimation, neutral 0%, blue +10% for bike underestimation; gray unknown. Resistance left to right, cadence bottom to top" +
                (cell?.let { "; recording resistance cell ${it.first}, ${45 + it.second * 5} rpm" } ?: "")
        }) {
            val w = size.width / 31
            val h = size.height / 16
            for (x in 0..30) for (y in 0..15) {
                val i = x * 16 + y
                val color = correctionColor(table?.correctionPercent?.getOrNull(i))
                val origin = Offset(x * w, (15 - y) * h)
                val block = Size(w - 1, h - 1)
                drawRect(color, origin, block)
                if (cell == (x to y)) {
                    drawRect(lerp(progressColor, Color.White, pulse * .35f), origin, block)
                    drawRect(progressColor.copy(alpha = .55f + pulse * .45f), origin, block,
                        style = Stroke((1f + pulse).dp.toPx()))
                }
            }
        }
    }
}

/** Mark the rider's actual collecting cell, not the next guided target or a stale saved effort. */
internal fun recordingCell(capture: CalibrationUiState, recording: Boolean): Pair<Int, Int>? {
    if (!recording || !capture.enabled || capture.resting || capture.pedalWatts == null ||
        capture.progress.phase !in listOf(CapturePhase.Settling, CapturePhase.Holding, CapturePhase.Saved)) return null
    val frame = capture.frame ?: return null
    if (frame.rpm !in 45.0..120.0) return null
    val x = calibrationCoordinate(capture.baseline, frame.ad.toDouble()) ?: return null
    return x.roundToInt().coerceIn(0, 30) to ((frame.rpm - 45) / 5).roundToInt().coerceIn(0, 15)
}
