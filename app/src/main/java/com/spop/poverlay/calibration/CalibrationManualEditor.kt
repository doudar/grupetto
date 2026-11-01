package com.spop.poverlay.calibration

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.spop.poverlay.ui.theme.Typography
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    var inputFocused by remember { mutableStateOf(false) }
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

    // Compose 1.6's non-platform measurement caps height to screenHeightDp and
    // rewrites the window bounds after layout. Use window measurement instead,
    // with an explicit MATCH_PARENT size below.
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = true,
        decorFitsSystemWindows = false, dismissOnClickOutside = false, dismissOnBackPress = !saving)) {
      val view = LocalView.current
      val window = (view.parent as DialogWindowProvider).window
      SideEffect {
          // A full-size Compose surface still sits inside a wrap-content dialog window.
          // Size the window too, and scope immersive mode to this editor only.
          window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
          // Compose applies IME insets below; do not also resize/pan the native window.
          window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
          WindowCompat.getInsetsController(window, view).apply {
              systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
              hide(WindowInsetsCompat.Type.systemBars())
          }
      }
      MaterialTheme(colors = darkColors(primary = EditorAccent, secondary = EditorAccent,
          background = EditorBackground, surface = EditorPanel, onPrimary = EditorBackground,
          onSecondary = EditorBackground, onSurface = Color.White), typography = Typography) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // Some Android dialog transitions resize the window and consume the IME
        // insets. Keep numeric controls usable in that case as well as edge-to-edge.
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0 || (inputFocused && maxHeight < 520.dp)
        Surface(Modifier.fillMaxSize().testTag("power-table-studio"), color = EditorBackground, contentColor = Color.White) {
            Column(Modifier.fillMaxSize().displayCutoutPadding().imePadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Power table studio", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("MANUAL CALIBRATION  /  31 x 16", fontSize = 11.sp, color = EditorAccent)
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
                        Text(if (saving) "Saving..." else if (active) "Save & apply" else "Save draft")
                    }
                }
                if (!keyboardOpen) Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton({ table = history.undo(); notice = "Undid the last change." }, enabled = editable && history.canUndo) { Text("Undo") }
                    OutlinedButton({ table = history.redo(); notice = "Restored the next change." }, enabled = editable && history.canRedo) { Text("Redo") }
                    OutlinedButton({ compare = !compare }, enabled = !saving) { Text(if (compare) "Show edited" else "Compare original") }
                    OutlinedButton({ zoom = !zoom }) { Text(if (zoom) "Fit table" else "Zoom in") }
                    Text("${table.gains.indices.count { abs(table.gains[it] - initial.gains[it]) > 1e-9 }} changed / " +
                        "${table.correctionPercent.count { it != null }}/496 covered", color = EditorMuted, fontSize = 12.sp)
                }
                if (compare) Text("ORIGINAL PREVIEW / Select Show edited to continue editing", color = EditorAccent, fontSize = 12.sp)
                if (!keyboardOpen) EditorQuickFixes(table, editable,
                    onFill = { commit(CalibrationManualEdit.fillGaps(table), "Filled every unknown cell from existing calibration. Covered values kept.") },
                    onSmooth = { commit(CalibrationManualEdit.smooth(table, table.gains.indices.toSet()), "Smoothed the whole table toward neighboring values.") },
                    onSoften = { commit(CalibrationManualEdit.soften(table, table.gains.indices.toSet()), "Reduced every correction by 20% toward zero.") },
                    onReset = { commit(CalibrationManualEdit.resetToRecorded(table), "Removed all manual edits; recorded fit restored.") })
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val wide = maxWidth >= 840.dp
                    val inspector: @Composable () -> Unit = {
                        EditorInspector(table, selected, editScope, { editScope = it }, blend, { blend = it }, editable, compactInput = keyboardOpen,
                            onInputFocus = { inputFocused = it },
                            onSet = { value -> commit(CalibrationManualEdit.setPercent(table, selection, value, blend), "Set ${selection.size} selected cell(s) to ${percentLabel(value)}${if (blend) " with blending" else ""}.") },
                            onNudge = { amount -> commit(CalibrationManualEdit.nudge(table, selection, amount, blend), "Adjusted selection by ${percentLabel(amount)} percentage points.") },
                            onSmooth = { commit(CalibrationManualEdit.smooth(table, selection), "Smoothed ${selection.size} cell(s) toward their neighbors.") },
                            onSoften = { commit(CalibrationManualEdit.soften(table, selection), "Reduced selected corrections by 20% toward zero.") },
                            onRevert = { commit(initial, "Restored the table from when this editor opened.") }, dirty = dirty)
                    }
                    val currentInspector by rememberUpdatedState(inspector)
                    val inspectorContent = remember { movableContentOf { currentInspector() } }
                    if (keyboardOpen) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Column(Modifier.widthIn(max = 520.dp).fillMaxWidth()) { inspectorContent() }
                    } else if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        EditorGrid(if (compare) initial else table, selection, selected, { selected = it }, zoom, Modifier.weight(1f).fillMaxHeight())
                        Column(Modifier.width(320.dp).fillMaxHeight()) { inspectorContent() }
                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        EditorGrid(if (compare) initial else table, selection, selected, { selected = it }, zoom, Modifier.weight(1f).fillMaxWidth())
                        Column(Modifier.fillMaxWidth()) { inspectorContent() }
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
}

@Composable
private fun EditorInspector(table: GrupettoCalibrationTable, selected: Int, scope: CalibrationEditScope,
    onScope: (CalibrationEditScope) -> Unit, blend: Boolean, onBlend: (Boolean) -> Unit, enabled: Boolean,
    compactInput: Boolean,
    onInputFocus: (Boolean) -> Unit,
    onSet: (Double) -> Unit, onNudge: (Double) -> Unit, onSmooth: () -> Unit,
    onSoften: () -> Unit, onRevert: () -> Unit, dirty: Boolean) {
    val percent = CalibrationManualEdit.percent(table.gains[selected])
    val completedInputFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishInput() {
        completedInputFocus.requestFocus()
        keyboard?.hide()
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable(selected, percent) { mutableStateOf(String.format(Locale.US, "%.1f", percent)) }
    val parsed = input.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in -35.0..35.0 }
    Column(Modifier.fillMaxWidth().focusRequester(completedInputFocus).focusable()
        .background(EditorPanel, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${45 + selected % 16 * 5} rpm / point ${selected / 16} / ${percentLabel(percent)}", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (!compactInput) TabRow(tab, backgroundColor = Color.Transparent) {
            listOf("Edit", "Scope", "Tools").forEachIndexed { index, label ->
                Tab(tab == index, { tab = index }, modifier = Modifier.height(36.dp), text = { Text(label, fontSize = 12.sp) })
            }
        }
        if (tab == 1 && !compactInput) {
        Text("Apply to", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        CalibrationEditScope.values().toList().chunked(2).forEach { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    OutlinedButton({ onScope(option) }, enabled = enabled, modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (scope == option) EditorAccent else EditorMuted,
                            backgroundColor = if (scope == option) EditorAccent.copy(alpha = .12f) else Color.Transparent)) {
                        Text(option.label, fontSize = 11.sp, fontWeight = if (scope == option) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(blend, onBlend, enabled = enabled, colors = SwitchDefaults.colors(checkedThumbColor = EditorAccent))
            Text("Blend into neighbors", fontSize = 14.sp)
        }
        Text("Blends over 2.5 resistance points and 15 rpm. Turn off for exact cells only.", fontSize = 11.sp, color = EditorMuted)
        Text("Raw resistance AD ${table.baseline[selected / 16]}", color = EditorMuted, fontSize = 12.sp)
        }
        if (tab == 0 || compactInput) {
        OutlinedTextField(input, { input = it }, enabled = enabled, label = { Text("Correction (%)") },
            placeholder = { Text("e.g. -5 or +8.5") }, singleLine = true, isError = parsed == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { finishInput() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { onInputFocus(it.isFocused) },
            colors = TextFieldDefaults.outlinedTextFieldColors(textColor = Color.White, unfocusedBorderColor = EditorMuted,
                focusedBorderColor = EditorAccent, focusedLabelColor = EditorAccent, unfocusedLabelColor = EditorMuted, cursorColor = EditorAccent))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ input = if (input.startsWith("-")) input.removePrefix("-") else "-" + input.removePrefix("+") }, enabled = enabled) { Text("+/-") }
            Button({ parsed?.let(onSet); finishInput() }, enabled = enabled && parsed != null, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(backgroundColor = EditorAccent, contentColor = EditorBackground)) { Text("Set correction") }
        }
        Text(if (parsed == null) "Enter a number from -35 to +35%." else "Relative to raw bike watts / negative lowers power.",
            fontSize = 11.sp, color = if (parsed == null) Color(0xFFFBBF24) else EditorMuted)
        if (!compactInput) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ onNudge(-1.0) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("-1%") }
            OutlinedButton({ onNudge(1.0) }, enabled = enabled, modifier = Modifier.weight(1f)) { Text("+1%") }
        }
        Text("${scope.label} / ${if (blend) "Blending on" else "Exact cells only"}", fontSize = 11.sp, color = EditorMuted)
        }
        if (tab == 2 && !compactInput) {
        Text("Selection tools", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        EditorTool("Smooth selection", "Gentle neighbor averaging to reduce abrupt changes.", enabled, onSmooth)
        EditorTool("Reduce corrections 20%", "Move the selection toward native bike power.", enabled, onSoften)
        TextButton(onRevert, enabled = enabled && dirty, modifier = Modifier.fillMaxWidth()) { Text("Revert this editing session") }
        Text("These tools use your selection. The quick fixes above the table affect the whole table. Every tool supports Undo.", color = EditorMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun EditorQuickFixes(table: GrupettoCalibrationTable, enabled: Boolean,
    onFill: () -> Unit, onSmooth: () -> Unit, onSoften: () -> Unit, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(EditorPanel, RoundedCornerShape(12.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Quick fixes / Whole table / Undo available", color = EditorAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        BoxWithConstraints {
            val columns = if (maxWidth >= 840.dp) 4 else 2
            val buttons: List<@Composable () -> Unit> = listOf(
                { EditorTool("Fill all gaps", "Interpolate unknown cells; keep covered values.", enabled &&
                    table.correctionPercent.any { it != null } && table.correctionPercent.any { it == null }, onFill) },
                { EditorTool("Smooth whole table", "Soften abrupt changes between neighbors.", enabled, onSmooth) },
                { EditorTool("Reduce all 20%", "Bring every correction closer to zero.", enabled, onSoften) },
                { EditorTool("Restore recorded fit", "Remove manual edits; keep recorded data.", enabled && table.manualOffsets.isNotEmpty(), onReset) }
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                buttons.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                        row.forEach { button -> Box(Modifier.weight(1f).fillMaxHeight()) { button() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTool(title: String, description: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(action, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(8.dp)) {
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
        Text("CADENCE: LOW TO HIGH    /    RESISTANCE: LEFT TO RIGHT", color = EditorMuted, fontSize = 11.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("calibration-grid-viewport")) {
            val density = LocalDensity.current
            // Round down in pixels so rounding 31 columns / 16 rows never exceeds
            // the viewport. Fit mode must not impose a minimum that requires scrolling.
            val cellWidth = if (zoom) 68.dp else with(density) {
                ((constraints.maxWidth - 40.dp.roundToPx()).coerceAtLeast(31) / 31).toDp()
            }
            val cellHeight = if (zoom) 48.dp else with(density) {
                ((constraints.maxHeight - 28.dp.roundToPx()).coerceAtLeast(16) / 16).toDp()
            }
            val axisFont = with(density) { (cellHeight - 2.dp).coerceAtLeast(1.dp).toSp().value.coerceAtMost(11f).sp }
            Column {
                Row {
                    Text("rpm", Modifier.width(40.dp).height(28.dp), color = EditorMuted, fontSize = 11.sp)
                    Row(if (zoom) Modifier.horizontalScroll(horizontal) else Modifier) {
                        for (x in 0..30) Box(Modifier.width(cellWidth).height(28.dp), contentAlignment = Alignment.Center) {
                            Text(if (zoom || x % 5 == 0) "$x" else "", color = EditorMuted, fontSize = 11.sp)
                        }
                    }
                }
                Row(if (zoom) Modifier.verticalScroll(vertical) else Modifier) {
                    Column {
                        for (y in 15 downTo 0) Box(Modifier.width(40.dp).height(cellHeight), contentAlignment = Alignment.CenterStart) {
                            Text("${45 + y * 5}", fontSize = axisFont, lineHeight = axisFont, maxLines = 1,
                                softWrap = false, color = EditorMuted)
                        }
                    }
                    Column(if (zoom) Modifier.horizontalScroll(horizontal) else Modifier) {
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
                                    if (zoom) Text(value?.let(::percentLabel) ?: "--", fontSize = 12.sp,
                                        color = if (value != null && abs(value) < 5) Color(0xFF101827) else Color.White)
                                    if (manual && zoom) Canvas(Modifier.align(Alignment.TopEnd).padding(3.dp).size(4.dp)) {
                                        drawCircle(EditorAccent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        CalibrationCorrectionLegend()
        Text(if (zoom) "Scroll to explore / dot = manual edit or fill / outline = selection" else "Tap any cell / Zoom in for values and larger touch targets",
            fontSize = 11.sp, color = EditorMuted)
    }
}
