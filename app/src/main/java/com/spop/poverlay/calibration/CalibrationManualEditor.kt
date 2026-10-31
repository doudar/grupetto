package com.spop.poverlay.calibration

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private val EditorBackground = Color(0xFF0C1420)
private val EditorPanel = Color(0xFF142132)
private val EditorAccent = Color(0xFF6EE7B7)
private val EditorMuted = Color(0xFFA8B9CD)
private fun percentLabel(value: Double) = String.format(Locale.US, "%+.1f%%", value)

@Composable
internal fun CalibrationManualEditor(initial: GrupettoCalibrationTable, active: Boolean,
    onSave: suspend (GrupettoCalibrationTable, GrupettoCalibrationTable) -> Unit, onClose: () -> Unit,
    history: CalibrationEditHistory = remember { CalibrationEditHistory(initial) }) {
    var table by remember { mutableStateOf(history.current) }
    var selected by rememberSaveable { mutableIntStateOf(15 * 16 + 7) }
    var editScope by rememberSaveable { mutableStateOf(CalibrationEditScope.Cell) }
    var blend by rememberSaveable { mutableStateOf(true) }
    var zoom by rememberSaveable { mutableStateOf(false) }
    var compare by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf("Select a cell to start. Changes stay in this preview until saved.") }
    val coroutineScope = rememberCoroutineScope()
    val dirty = table != initial
    val selection = editScope.indices(selected)
    val editable = !saving && !compare
    fun commit(next: GrupettoCalibrationTable, message: String) {
        table = history.commit(next); notice = message; error = null
    }
    fun close() { if (!saving) { if (dirty) discard = true else onClose() } }

    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false, dismissOnBackPress = !saving)) {
      MaterialTheme(colors = darkColors(primary = EditorAccent, secondary = EditorAccent,
          background = EditorBackground, surface = EditorPanel, onPrimary = EditorBackground,
          onSecondary = EditorBackground, onSurface = Color.White)) {
        Surface(Modifier.fillMaxSize(), color = EditorBackground, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Power table studio", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("MANUAL CALIBRATION  /  31 × 16", fontSize = 11.sp, color = EditorAccent)
                    }
                    TextButton({ close() }, enabled = !saving) { Text("Close", color = EditorMuted) }
                    Button(onClick = {
                        saving = true; error = null
                        coroutineScope.launch {
                            try { onSave(initial, table); onClose() }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Could not save. Your preview is still here." }
                            finally { saving = false }
                        }
                    }, enabled = dirty && !saving, colors = ButtonDefaults.buttonColors(backgroundColor = EditorAccent, contentColor = EditorBackground)) {
                        Text(if (saving) "Saving…" else if (active) "Save & apply" else "Save draft")
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ table = history.undo(); notice = "Undid the last change." }, enabled = editable && history.canUndo) { Text("Undo") }
                    OutlinedButton({ table = history.redo(); notice = "Restored the next change." }, enabled = editable && history.canRedo) { Text("Redo") }
                    OutlinedButton({ compare = !compare }, enabled = !saving) { Text(if (compare) "Show edited" else "Compare original") }
                    OutlinedButton({ zoom = !zoom }) { Text(if (zoom) "Fit table" else "Zoom in") }
                    Text("${table.gains.indices.count { abs(table.gains[it] - initial.gains[it]) > 1e-9 }} changed · " +
                        "${table.correctionPercent.count { it != null }}/496 covered", color = EditorMuted, fontSize = 12.sp)
                }
                if (compare) Text("ORIGINAL PREVIEW · Select Show edited to continue editing", color = EditorAccent, fontSize = 12.sp)
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 840.dp
                    val inspector: @Composable () -> Unit = {
                        EditorInspector(table, selected, editScope, { editScope = it }, blend, { blend = it }, editable,
                            onSet = { value -> commit(CalibrationManualEdit.setPercent(table, selection, value, blend), "Set ${selection.size} selected cell(s) to ${percentLabel(value)}${if (blend) " with blending" else ""}.") },
                            onNudge = { amount -> commit(CalibrationManualEdit.nudge(table, selection, amount, blend), "Adjusted selection by ${percentLabel(amount)} percentage points.") },
                            onFill = { commit(CalibrationManualEdit.fillGaps(table), "Filled unknown cells; existing covered cells kept. Edge values extend the nearest trend.") },
                            onSmooth = { commit(CalibrationManualEdit.smooth(table, selection), "Smoothed ${selection.size} cell(s) toward their neighbors.") },
                            onSoften = { commit(CalibrationManualEdit.soften(table, selection), "Reduced selected corrections by 20% toward zero.") },
                            onReset = { commit(CalibrationManualEdit.resetToRecorded(table), "Removed all manual edits; recorded fit restored.") },
                            onRevert = { commit(initial, "Restored the table from when this editor opened.") }, dirty = dirty)
                    }
                    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        EditorGrid(if (compare) initial else table, selection, selected, { selected = it }, zoom, Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { inspector() }
                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        EditorGrid(if (compare) initial else table, selection, selected, { selected = it }, zoom, Modifier.weight(1f).fillMaxWidth())
                        Column(Modifier.fillMaxWidth().heightIn(max = 230.dp).verticalScroll(rememberScrollState())) { inspector() }
                    }
                }
                Text(error ?: notice, color = if (error != null) Color(0xFFFBBF24) else EditorMuted, fontSize = 12.sp)
            }
            if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unsaved edits?") },
                text = { Text("Your saved power table has not changed.") },
                confirmButton = { TextButton(onClose) { Text("Discard edits") } },
                dismissButton = { TextButton({ discard = false }) { Text("Keep editing") } })
        }
      }
    }
}

@Composable
private fun EditorInspector(table: GrupettoCalibrationTable, selected: Int, scope: CalibrationEditScope,
    onScope: (CalibrationEditScope) -> Unit, blend: Boolean, onBlend: (Boolean) -> Unit, enabled: Boolean,
    onSet: (Double) -> Unit, onNudge: (Double) -> Unit, onFill: () -> Unit, onSmooth: () -> Unit,
    onSoften: () -> Unit, onReset: () -> Unit, onRevert: () -> Unit, dirty: Boolean) {
    val percent = CalibrationManualEdit.percent(table.gains[selected])
    var input by rememberSaveable(selected, percent) { mutableStateOf(String.format(Locale.US, "%.1f", percent)) }
    val parsed = input.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in -35.0..35.0 }
    Column(Modifier.fillMaxWidth().background(EditorPanel, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${45 + selected % 16 * 5} rpm · point ${selected / 16}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Raw resistance AD ${table.baseline[selected / 16]}", color = EditorMuted, fontSize = 12.sp)
        Text("${percentLabel(percent)} correction", color = EditorAccent, fontSize = 24.sp)
        Text(when {
            selected in table.manualCoverage -> "Manually edited / extended"
            selected in table.recordedCoverage -> "Near recorded efforts"
            else -> "No nearby data · fitted value shown"
        }, fontSize = 12.sp, color = EditorMuted)
        Text("Apply to", fontWeight = FontWeight.Bold)
        CalibrationEditScope.values().toList().chunked(2).forEach { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    OutlinedButton({ onScope(option) }, enabled = enabled, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (scope == option) EditorAccent else EditorMuted,
                            backgroundColor = if (scope == option) EditorAccent.copy(alpha = .12f) else Color.Transparent)) {
                        Text((if (scope == option) "✓ " else "") + option.label, fontSize = 11.sp)
                    }
                }
            }
        }
        OutlinedTextField(input, { input = it }, enabled = enabled, label = { Text("Correction (%)") },
            placeholder = { Text("e.g. -5 or +8.5") }, singleLine = true, isError = parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
            colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Color.White, unfocusedBorderColor = EditorMuted,
                focusedBorderColor = EditorAccent, focusedLabelColor = EditorAccent, unfocusedLabelColor = EditorMuted, cursorColor = EditorAccent))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ input = if (input.startsWith("-")) input.removePrefix("-") else "-" + input.removePrefix("+") }, enabled = enabled) { Text("+/−") }
            Button({ parsed?.let(onSet) }, enabled = enabled && parsed != null, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(backgroundColor = EditorAccent, contentColor = EditorBackground)) { Text("Set correction") }
        }
        Text(if (parsed == null) "Enter a number from −35 to +35%." else "Relative to raw bike watts · negative lowers power.",
            fontSize = 11.sp, color = if (parsed == null) Color(0xFFFBBF24) else EditorMuted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ onNudge(-1.0) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("−1%") }
            OutlinedButton({ onNudge(1.0) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+1%") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(blend, onBlend, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = EditorAccent))
            Text("Blend into neighbors", fontSize = 14.sp)
        }
        Text("Cell and row edits taper over 2.5 resistance points and 15 rpm. Turn blending off for exact cells only.", fontSize = 11.sp, color = EditorMuted)
        Divider(color = EditorMuted.copy(alpha = .2f))
        Text("One-touch tools", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        EditorTool("Fill all gaps", "Interpolate all unknown cells. Preserve covered values.", enabled &&
            table.correctionPercent.any { it != null } && table.correctionPercent.any { it == null }, onFill)
        EditorTool("Smooth selection", "Gentle neighbor averaging to reduce abrupt changes.", enabled, onSmooth)
        EditorTool("Reduce corrections 20%", "Move the selection toward native bike power.", enabled, onSoften)
        EditorTool("Restore recorded fit", "Remove all manual changes, including earlier sessions.", enabled && table.manualOffsets.isNotEmpty(), onReset)
        TextButton(onRevert, enabled = enabled && dirty, modifier = Modifier.fillMaxWidth()) { Text("Revert this editing session") }
        Text("Fill and restore affect the whole table. Smooth and reduce use your selection. Every tool supports Undo.", color = EditorMuted, fontSize = 11.sp)
    }
}

@Composable
private fun EditorTool(title: String, description: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 14.sp)
            Text(description, color = EditorMuted, fontSize = 11.sp, fontWeight = FontWeight.Normal)
        }
    }
}

@Composable
private fun EditorGrid(table: GrupettoCalibrationTable, selection: Set<Int>, selected: Int, onSelect: (Int) -> Unit,
    zoom: Boolean, modifier: Modifier) {
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    Column(modifier.background(EditorPanel, RoundedCornerShape(12.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("CADENCE ↑    RESISTANCE POINT →", color = EditorMuted, fontSize = 11.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cellWidth = if (zoom) 68.dp else ((maxWidth - 40.dp) / 31).coerceAtLeast(12.dp)
            val cellHeight = if (zoom) 48.dp else ((maxHeight - 28.dp) / 16).coerceAtLeast(12.dp)
            Column {
                Row {
                    Text("rpm", Modifier.width(40.dp).height(28.dp), color = EditorMuted, fontSize = 11.sp)
                    Row(Modifier.horizontalScroll(horizontal)) {
                        for (x in 0..30) Box(Modifier.width(cellWidth).height(28.dp), contentAlignment = Alignment.Center) {
                            Text(if (zoom || x % 5 == 0) "$x" else "", color = EditorMuted, fontSize = 11.sp)
                        }
                    }
                }
                Row(Modifier.verticalScroll(vertical)) {
                    Column {
                        for (y in 15 downTo 0) Box(Modifier.width(40.dp).height(cellHeight), contentAlignment = Alignment.CenterStart) {
                            Text("${45 + y * 5}", fontSize = 11.sp, color = EditorMuted)
                        }
                    }
                    Column(Modifier.horizontalScroll(horizontal)) {
                        for (y in 15 downTo 0) Row {
                            for (x in 0..30) {
                                val index = x * 16 + y
                                val value = table.correctionPercent.getOrNull(index)
                                val manual = index in table.manualCoverage
                                Box(Modifier.size(cellWidth, cellHeight).padding(1.dp)
                                    .background(correctionColor(value), RoundedCornerShape(3.dp))
                                    .then(if (index in selection) Modifier.border(if (index == selected) 3.dp else 1.dp,
                                        if (index == selected) EditorAccent else Color.White, RoundedCornerShape(3.dp)) else Modifier)
                                    .selectable(index in selection, onClick = { onSelect(index) })
                                    .semantics { contentDescription = "Point $x, ${45 + y * 5} rpm, " +
                                        (value?.let(::percentLabel) ?: "no nearby data") + if (manual) ", manual" else "" },
                                    contentAlignment = Alignment.Center) {
                                    if (zoom) Text(value?.let(::percentLabel) ?: "—", fontSize = 12.sp,
                                        color = if (value != null && abs(value) < 5) Color(0xFF101827) else Color.White)
                                    if (manual && zoom) Text("•", Modifier.align(Alignment.TopEnd).padding(end = 3.dp),
                                        color = EditorAccent, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        CalibrationCorrectionLegend()
        Text(if (zoom) "Scroll to explore · • manually edited / filled · outline = selection" else "Tap any cell · Zoom in for values and larger touch targets",
            fontSize = 11.sp, color = EditorMuted)
    }
}
