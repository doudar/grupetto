package com.spop.poverlay.calibration

import androidx.compose.animation.core.*
import com.spop.poverlay.ui.components.GrupettoTabs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
    fileControls: @Composable () -> Unit = {}, onManualEdit: (() -> Unit)? = null,
    livePosition: CalibrationTablePosition? = null,
    captureSettings: CalibrationCaptureSettings = CalibrationCaptureSettings(),
    onCaptureSettings: ((CalibrationCaptureSettings) -> Unit)? = null,
    onRefit: (() -> Unit)? = null) {
    var confirmClear by remember { mutableStateOf(false) }
    var showInformation by remember { mutableStateOf(false) }
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    // Older versions saved Backups as index 2 before the Fit details tab was removed.
    val selectedTab = tab.coerceIn(0, 1)
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen) AlertDialog(onDismissRequest = { settingsOpen = false },
        title = { Text("Capture settings", Modifier.testTag("stream-capture-settings-title")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settle for 2 seconds with the knob unchanged and cadence inside one 5-rpm cell band.")
            Text("Collect about once per second. Each cell keeps its latest 100 accepted seconds across visits.")
            Text("An isolated ratio glitch is held aside. Three consecutive ratios within 3 percentage points establish a new level. No neighbor smoothing is applied.")
            Text("These stream thresholds are fixed. Pausing keeps accepted observations; recording never resumes silently after restart.")
        } }, confirmButton = { TextButton({ settingsOpen = false }) { Text("Close") } })
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Clear Grupetto table?") },
            text = { Text("Delete all saved Grupetto efforts and manual edits, stop recording, and turn correction off? " +
                "This cannot be undone without loading an exported backup. The bike's own calibration and exported backups are unchanged.") },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text("Clear Grupetto table", color = MaterialTheme.colors.error) } },
            dismissButton = {
                val keepFocus = remember { FocusRequester() }
                TextButton({ confirmClear = false }, Modifier.focusProperties { canFocus = true }.focusRequester(keepFocus)) { Text("Keep table") }
                // The dialog owns a separate composition. Request only after its button is attached.
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    keepFocus.requestFocus()
                }
            })
    }
    if (showInformation) AlertDialog(onDismissRequest = { showInformation = false },
        title = { Text("Calibration information") }, text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(state.message)
                Text("Record + Activate: each usable saved effort updates the active table automatically. " +
                    "One valid saved effort is enough to activate. Activate is still required the first time and after loading a backup.")
                Text("Colors show the ${if (state.active) "active" else "preview"} correction as a percentage of raw bike watts. " +
                    "Red: bike overestimated. Blue: bike underestimated. Scale saturates at -10% / +10%.")
                state.errors?.let {
                    Text("Average absolute error compares the same ${it.count} saved pedal measurements, not live wattage. Corrected error uses " +
                        if (it.preview) "the preview table." else "the active table.")
                    Text("Corrected error can increase when edits or the current table fit past efforts less well.")
                }
                state.table?.let { table ->
                    if (table.manualCoverage.isNotEmpty()) Text("Shared correction includes edits; future recordings update the same table.")
                    Text(String.format(Locale.US, "Saved-effort RMSE %.2f to %.2f W", table.beforeRmse, table.afterRmse))
                    if (!table.canActivate) Text("Record at least one valid effort to activate.")
                }
                Text("In exported files, pedalWatts is the editable calibration target. recordedPedalWatts keeps the original pedal measurement after an edit; when absent, pedalWatts is still the original. Error scores and refitting use the original measurement.")
                Text("Error scores describe the saved efforts and are informational. Streaming: hold the knob still for two seconds and keep cadence in one 5-rpm cell band. Collect distinct paired readings about once a second; each cell retains its latest 100 accepted seconds across visits. Legacy efforts remain aggregates, not raw seconds. Three consistent shifted ratios establish a new level; isolated outliers are held aside. The bar transition is visual only. Pulse: cyan shows your current cell even when not recording; orange settles, yellow holds, green saves. A separate pulsing green border marks the nearest filled cell used when your current cell has no data.")
            }
        }, confirmButton = { TextButton({ showInformation = false }) { Text("Close") } })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Grupetto table", fontSize = 18.sp, lineHeight = 22.sp)
                Text("${state.count} saved", fontSize = 14.sp, lineHeight = 18.sp, color = Color(0xFF6EE7B7))
            }
            IconButton({ showInformation = true }) { Icon(Icons.Outlined.Info, contentDescription = "Calibration information") }
            Switch(state.recording, onRecord, modifier = Modifier.semantics { contentDescription = "Record calibration" })
            Text("Record", fontSize = 16.sp)
            Spacer(Modifier.width(16.dp))
            Switch(state.active, onActivate, enabled = state.active || state.canActivate,
                modifier = Modifier.semantics { contentDescription = "Activate calibration" })
            Text("Activate", fontSize = 16.sp)
        }
        GrupettoTabs(selectedTab, listOf("Table", "Backups"), { tab = it })
        CaptureErrorNotice(capture)
        if (selectedTab == 0) {
        if (!meterReady) Text("Connect a power meter to record", fontSize = 14.sp, lineHeight = 18.sp, color = Color(0xFFFBBF24))
        CalibrationMetrics(metrics, showCorrected = true)
        (state.displayTable ?: state.table)?.takeIf { it.streamSeconds > 0 }?.let {
            Text("${it.streamSeconds} paired seconds · ${it.legacyEffortCount} legacy effort aggregates", fontSize = 11.sp,
                color = Color(0xFF9EAEC0))
        }
        GrupettoCoverageChart(state.displayTable ?: state.table, capture, state.recording && meterReady, livePosition)
        if (!capture.progress.streaming) CaptureHoldFeedback(capture, state.recording)
        CalibrationCorrectionLegend()
        CalibrationErrorSummary(state.errors, onRefit,
            refitEnabled = !state.recording && (state.displayTable ?: state.table) != null)
        CalibrationTableActions(onGuide, onManualEdit, onCaptureSettings?.let { { settingsOpen = true } },
            { confirmClear = true }, manualEnabled = !state.recording && (state.displayTable ?: state.table) != null)
        if (hasPendingCalibrationFit(state.active, state.recording, state.table, state.displayTable, state.count, state.activeCount)) {
            TextButton({ onActivate(true) }) { Text("Apply latest fit") }
        }
        if (state.recording) Text("Pause recording to edit the power table manually.", fontSize = 12.sp, color = Color(0xFF9EAEC0))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(when {
                state.active && !state.verified -> "Active · waiting for bike"
                state.active -> "Active: ${state.activeCount} efforts" + if (state.recording) " · auto-update on" else ""
                state.table?.canActivate == true -> "Draft ready to activate"
                state.savedCount > 0 -> "Saved ${state.savedCount}-effort table ready to activate"
                else -> "Record at least one valid effort to activate"
            }, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF6EE7B7))
        }
        }
        if (selectedTab == 0 && capture.progress.streaming) CaptureHoldFeedback(capture, state.recording)
        if (selectedTab == 1) {
            Text("Save or share the current calibration, or load a backup.", fontSize = 14.sp)
            fileControls()
        }
    }
}

/** Compact horizontal controls on a tablet; wrap without shrinking touch targets on narrow screens. */
@Composable
internal fun CalibrationTableActions(onGuide: () -> Unit, onManualEdit: (() -> Unit)?,
    onCaptureSettings: (() -> Unit)?, onClear: () -> Unit, manualEnabled: Boolean) {
    val actions = listOfNotNull(
        Triple("Guided capture", onGuide, true),
        onManualEdit?.let { Triple("Manual editor", it, manualEnabled) },
        onCaptureSettings?.let { Triple("Capture settings", it, true) },
        Triple("Clear table...", onClear, true))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 4 else if (maxWidth >= 280.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.forEach { (label, action, enabled) ->
                        OutlinedButton(action, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)) {
                            Text(label, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CalibrationCorrectionLegend() {
    Column {
        Canvas(Modifier.fillMaxWidth().height(7.dp)) {
            drawRect(Brush.horizontalGradient(listOf(CorrectionOverColor, CorrectionZeroColor, CorrectionUnderColor)))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-10% · bike high", fontSize = 11.sp, lineHeight = 15.sp)
            Text("0%", fontSize = 11.sp, lineHeight = 15.sp)
            Text("+10% · bike low", fontSize = 11.sp, lineHeight = 15.sp)
        }
        Text("Correction · gray: no saved cell data", fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF9EAEC0))
    }
}

@Composable
private fun CalibrationErrorSummary(errors: CalibrationErrorComparison?, onRefit: (() -> Unit)?, refitEnabled: Boolean) {
    fun watts(value: Double?) = value?.let { String.format(Locale.US, "%.1f W", it) } ?: "-"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text("Average error vs pedals" + when {
                errors?.afterWatts == null -> ""
                errors.preview -> " · preview"
                else -> " · active table"
            }, fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Raw bike error", fontSize = 14.sp, lineHeight = 18.sp)
                Spacer(Modifier.weight(1f))
                Text(watts(errors?.beforeWatts), fontSize = 24.sp, lineHeight = 28.sp)
            }
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            if (onRefit != null) OutlinedButton(onRefit, enabled = refitEnabled,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Calculate New Solution", fontSize = 12.sp, lineHeight = 16.sp)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Corrected error", fontSize = 14.sp, lineHeight = 18.sp)
                Spacer(Modifier.weight(1f))
                val improved = errors?.afterWatts?.let { it < errors.beforeWatts }
                Text(watts(errors?.afterWatts), fontSize = 24.sp, lineHeight = 28.sp,
                    color = when (improved) { true -> Color(0xFF6EE7B7); false -> Color(0xFFFBBF24); null -> Color(0xFF9EAEC0) })
            }
        }
    }
}

@Composable
internal fun GrupettoCoverageChart(table: GrupettoCalibrationTable?, capture: CalibrationUiState, recording: Boolean,
    livePosition: CalibrationTablePosition?, now: Long = android.os.SystemClock.elapsedRealtime()) {
    val recordingCell = recordingCell(capture, recording)
    val highlights = liveTableHighlights(livePosition, table, now)
        ?: recordingCell?.let {
            liveTableHighlights(capture.frame?.let { CalibrationTablePosition(it, capture.baseline) }, table, now)
        }
    val cell = highlights?.current
    val fallbackCell = highlights?.fallback
    val pulse = if (cell != null) {
        val transition = rememberInfiniteTransition(label = "Current and fallback cells")
        transition.animateFloat(.25f, 1f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "Table cell pulse").value
    } else 0f
    val progressColor = if (cell != null && cell == recordingCell) captureProgressColor(capture.progress) else Color(0xFF22D3EE)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("120\nrpm\n\n45", fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.width(32.dp))
        Canvas(Modifier.weight(1f).height(80.dp).semantics {
            contentDescription = "Correction percentage: red -10% for bike overestimation, neutral 0%, blue +10% for bike underestimation; gray unknown. Resistance left to right, cadence bottom to top" +
                (cell?.let { "; ${if (it == recordingCell) "recording" else "current"} resistance cell ${it.first}, ${45 + it.second * 5} rpm" } ?: "") +
                (fallbackCell?.let { "; green pulsing border: fallback resistance cell ${it.first}, ${45 + it.second * 5} rpm" } ?: "")
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
            // Draw after the grid so adjacent cell fills cannot cover the source-cell border.
            fallbackCell?.let { (x, y) ->
                drawRect(Color(0xFF4ADE80).copy(alpha = .45f + pulse * .55f),
                    Offset(x * w, (15 - y) * h), Size(w - 1, h - 1),
                    style = Stroke((1f + pulse).dp.toPx()))
            }
        }
    }
}

/** Mark the rider's actual collecting cell, not the next guided target or a stale saved effort. */
internal fun recordingCell(capture: CalibrationUiState, recording: Boolean): Pair<Int, Int>? {
    if (!recording || !capture.enabled || capture.resting || capture.pedalWatts == null ||
        capture.progress.phase !in listOf(CapturePhase.Settling, CapturePhase.Holding, CapturePhase.Saved, CapturePhase.Collecting, CapturePhase.Outlier)) return null
    val frame = capture.frame ?: return null
    if (frame.rpm !in 45.0..120.0) return null
    val x = calibrationCoordinate(capture.baseline, frame.ad.toDouble()) ?: return null
    return x.roundToInt().coerceIn(0, 30) to ((frame.rpm - 45) / 5).roundToInt().coerceIn(0, 15)
}
