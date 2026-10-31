package com.spop.poverlay.calibration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*
import java.util.Locale

private val CaptureGreen = Color(0xFF6EE7B7)
private val CaptureBlue = Color(0xFF60A5FA)
private val CaptureMuted = Color(0xFF9EAEC0)

@Composable
fun CalibrationPanel(state: CalibrationUiState, onEnabled: (Boolean) -> Unit, onClear: () -> Unit,
    onRest: (Boolean) -> Unit = {}, onEasier: () -> Unit = {},
    onSkip: () -> Unit = {}, onTarget: (CalibrationTarget) -> Unit = {}, onHarder: () -> Unit = {},
    onBackground: () -> Unit = {}, localCalibration: GrupettoCalibrationUiState? = null,
    onLocalReview: () -> Unit = {}, meterReady: Boolean = false, liveMetrics: CalibrationLiveMetrics? = null,
    boardContent: @Composable () -> Unit = {}) {
    var review by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().heightIn(max = calibrationContentHeight()).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Button({ onEnabled(!state.enabled) }, Modifier.heightIn(min = 48.dp), enabled = state.enabled || meterReady) {
                Text(if (state.enabled) "Pause capture" else "Start capture")
            }
            if (localCalibration != null) OutlinedButton(onLocalReview,
                Modifier.padding(start = 8.dp).heightIn(min = 48.dp)) { Text("Table") }
            Spacer(Modifier.weight(1f))
            Text("${localCalibration?.count ?: state.sampleCount} saved", color = CaptureGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            TextButton({ review = !review }, Modifier.heightIn(min = 48.dp)) { Text(if (review) "Ride view" else "Review") }
        }
        CaptureErrorNotice(state)
        if (!meterReady) Text("Connect a power meter to record", color = CaptureMuted, fontSize = 14.sp)
        if (review) {
            localCalibration?.let { local ->
                Text("Grupetto power table", fontSize = 22.sp)
                val table = local.table
                Text(when {
                    local.active && local.recording -> "Recording · auto-update on"
                    local.active -> "Table activated"
                    table?.ready == true -> "Draft ready to activate"
                    local.canActivate -> "Saved table ready to activate"
                    else -> "More ride coverage needed"
                }, color = CaptureGreen, fontSize = 18.sp)
                Text("${local.count} saved efforts · ${table?.cells ?: 0} measured cells", color = CaptureMuted)
                table?.let {
                    Text(String.format(Locale.US, "Validation error %.1f to %.1f W", it.beforeRmse, it.afterRmse))
                }
                OutlinedButton(onLocalReview, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Grupetto table controls") }
                Divider(Modifier.padding(vertical = 4.dp), color = Color(0xFF43576D))
            }
            if (localCalibration == null) {
                Text("Sensor-board fit · ${state.fit?.supportedPoints?.size ?: 0} / 29 points", fontSize = 18.sp)
                Text(if (state.fit?.ready == true) "Board fit ready for review" else "Board fit needs more exact repeats", color = CaptureMuted)
                state.fit?.let { fit ->
                    if (fit.beforeRmse != null && fit.afterRmse != null) Text(String.format(Locale.US,
                        "Estimated error %.1f to %.1f W · %d validation efforts", fit.beforeRmse, fit.afterRmse, fit.validationCount))
                    if (fit.conflicts > 0) Text("${fit.conflicts} points disagree across cadences", color = Color(0xFFFBBF24))
                }
                CaptureChart(state.recent)
                Text("Collection never changes the bike table.", color = CaptureMuted, fontSize = 13.sp)
                boardContent()
                if (confirmClear) {
                    Text("Delete saved efforts? Bike table stays unchanged.")
                    Row {
                        TextButton({ confirmClear = false; onClear() }) { Text("Delete efforts") }
                        TextButton({ confirmClear = false }) { Text("Keep data") }
                    }
                } else TextButton({ confirmClear = true }) { Text("Clear capture…") }
            } else CaptureChart(state.recent)
        } else {
            CalibrationMetrics(liveMetrics ?: CalibrationLiveMetrics(state.frame?.nativeWatts, state.pedalWatts?.toDouble(),
                state.frame?.rpm, state.frame?.resistance?.toDouble()), showCorrected = liveMetrics != null)
            val target = state.target
            val delta = if (target != null && state.frame != null) target.ad - state.frame.ad else 0
            val aligned = state.frame?.let { target?.containsResistance(it.ad.toDouble()) } == true
            val cue = when {
                !state.enabled -> "Ready when you are"
                state.resting -> "Recover"
                state.frame == null -> "Waiting for bike + pedals"
                state.mode == CalibrationMode.Guided && target != null && !aligned -> if (delta > 0) "More resistance" else "Less resistance"
                state.mode == CalibrationMode.Guided && target != null && !target.containsCadence(state.frame.rpm) -> "Aim for ${target.rpm} rpm"
                else -> state.status
            }
            if (state.error == null) Box(Modifier.fillMaxWidth().background(Color(0xFF111B27), RoundedCornerShape(14.dp)).padding(8.dp)) {
                if (state.resting && state.enabled) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 80.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text("Recover", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CaptureGreen)
                        Button({ onRest(false) }, Modifier.width(220.dp).heightIn(min = 48.dp)) {
                            Text("Resume", fontSize = 20.sp)
                        }
                    }
                } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CaptureRing(state.progress, state.enabled)
                    Column(Modifier.weight(1f)) {
                        Text(cue, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = CaptureGreen)
                        Text(when {
                            !state.enabled -> if (state.sampleCount == 0) "Warm up · zero pedals in their app" else state.status
                            state.mode == CalibrationMode.Guided && target != null -> "${target.rpm} rpm · anywhere in green · hold 8 sec"
                            else -> "Ride normally · steady moments save automatically"
                        }, fontSize = 14.sp, color = CaptureMuted)
                        if (state.mode == CalibrationMode.Guided && target != null && state.frame != null) {
                            KnobTarget(target.markerPosition(state.frame.ad.toDouble()), aligned)
                        }
                    }
                }
            }
            if (state.mode == CalibrationMode.Guided) {
                if (state.targets.isNotEmpty()) Column {
                    state.targets.groupBy { it.rpm }.values.forEach { row ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${row.first().rpm} rpm", Modifier.width(60.dp), fontSize = 13.sp, color = CaptureMuted)
                            row.forEach { cell ->
                                val selected = cell.point == target?.point && cell.rpm == target?.rpm
                                Box(Modifier.weight(1f).height(48.dp)
                                    .background(if (selected) Color(0xFF263B50) else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { onTarget(cell) }.semantics { contentDescription = "Resistance band ${cell.point}, ${cell.rpm} rpm, ${min(3, cell.repeats)} of 3 saved, " +
                                        (cell.approximateWatts?.let { "approximately $it watts" } ?: "learning watts") },
                                    contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(cell.approximateWatts?.let { "~$it W" } ?: "Learning W", fontSize = 14.sp,
                                            lineHeight = 18.sp, fontWeight = FontWeight.Bold, color = if (selected) CaptureGreen else CaptureMuted)
                                        Canvas(Modifier.width(60.dp).height(18.dp)) {
                                            repeat(3) { effort ->
                                                val center = Offset((effort * 20 + 10).dp.toPx(), size.height / 2)
                                                if (effort < cell.repeats) drawCircle(CaptureGreen, 4.dp.toPx(), center)
                                                else drawCircle(CaptureGreen, 4.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onEasier, Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = state.targets.minOfOrNull { it.point }?.let { it > 1 } == true) { Text("Easier") }
                    Button(onHarder, Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = state.targets.minOfOrNull { it.point }?.let { it < 25 } == true) { Text("Harder") }
                    OutlinedButton({ onRest(true) }, Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = state.enabled && !state.resting) { Text("Rest") }
                    OutlinedButton(onSkip, Modifier.weight(1f).heightIn(min = 48.dp), enabled = target != null) { Text("Skip") }
                    Button(onBackground, Modifier.weight(2f).heightIn(min = 48.dp)) { Text("Table view") }
                }
            } else {
                CaptureChart(state.recent)
                Button(onBackground, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Table view") }
            }
            Text(if (localCalibration?.recording == true) "Recording Grupetto calibration · rest anytime" else "${state.progress.totalSeconds}-second efforts · rest anytime", color = CaptureMuted, fontSize = 12.sp)
        }
    }
}

@Composable private fun CaptureRing(progress: CaptureProgress, active: Boolean) {
    val progressColor = if (active) captureProgressColor(progress) else CaptureMuted
    Box(Modifier.size(72.dp).semantics { contentDescription = "Effort progress ${progress.seconds} of ${progress.totalSeconds} seconds" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            drawArc(Color(0xFF304255), -90f, 360f, false, style = Stroke(7.dp.toPx()))
            drawArc(progressColor, -90f, if (active) 360 * progress.fraction else 0f, false, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
            if (!active) {
                drawLine(CaptureGreen, Offset(size.width * .42f, size.height * .35f),
                    Offset(size.width * .42f, size.height * .65f), 4.dp.toPx(), StrokeCap.Round)
                drawLine(CaptureGreen, Offset(size.width * .58f, size.height * .35f),
                    Offset(size.width * .58f, size.height * .65f), 4.dp.toPx(), StrokeCap.Round)
            } else if (progress.phase == CapturePhase.Saved) {
                val bend = Offset(size.width * .45f, size.height * .64f)
                drawLine(CaptureGreen, Offset(size.width * .30f, size.height * .49f), bend, 4.dp.toPx(), StrokeCap.Round)
                drawLine(CaptureGreen, bend, Offset(size.width * .70f, size.height * .36f), 4.dp.toPx(), StrokeCap.Round)
            }
        }
        if (active && progress.phase != CapturePhase.Saved) Text("${progress.remaining}s",
            color = progressColor, fontSize = 29.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun KnobTarget(position: Float, aligned: Boolean) {
    Canvas(Modifier.fillMaxWidth().height(24.dp).semantics { contentDescription = if (aligned) "Resistance in green band; hold this shift" else "Shift until marker is anywhere in green" }) {
        val mid = size.width / 2
        val travel = mid - 8.dp.toPx()
        drawLine(Color(0xFF43576D), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4.dp.toPx(), StrokeCap.Round)
        drawRect(CaptureGreen.copy(alpha = .35f), Offset(mid - travel * .5f, 0f), Size(travel, size.height))
        drawCircle(if (aligned) CaptureGreen else CaptureBlue, 6.dp.toPx(), Offset(mid + position * travel, size.height / 2))
    }
}

@Composable private fun CaptureChart(samples: List<CalibrationSample>) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recent efforts", fontSize = 12.sp, color = CaptureMuted)
            CaptureLegend("Bike", CaptureBlue)
            CaptureLegend("Pedals", CaptureGreen)
        }
        Canvas(Modifier.fillMaxWidth().height(55.dp).semantics { contentDescription = "Recent bike and pedal power comparison" }) {
            val data = samples.takeLast(16)
            if (data.isEmpty()) return@Canvas
            val scale = data.maxOf { max(it.nativeWatts, it.pedalWatts) }.coerceAtLeast(1.0)
            val step = size.width / data.size
            data.forEachIndexed { i, sample ->
                val bike = (sample.nativeWatts / scale * size.height).toFloat()
                val pedal = (sample.pedalWatts / scale * size.height).toFloat()
                drawRect(CaptureBlue, Offset(i * step, size.height - bike), Size(step * .35f, bike))
                drawRect(CaptureGreen, Offset(i * step + step * .4f, size.height - pedal), Size(step * .35f, pedal))
            }
        }
    }
}

@Composable private fun CaptureLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(6.dp)) { drawCircle(color) }
        Text(label, fontSize = 12.sp, color = color)
    }
}
