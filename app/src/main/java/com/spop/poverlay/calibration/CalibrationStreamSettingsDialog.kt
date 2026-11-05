package com.spop.poverlay.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun CalibrationStreamSettingsDialog(settings: CalibrationStreamSettings,
    onSave: (CalibrationStreamSettings) -> Unit, onClose: () -> Unit) {
    var settle by remember { mutableStateOf(settings.settleSeconds.toString()) }
    var cadence by remember { mutableStateOf(settings.cadenceWindowRpm.toString()) }
    var resistance by remember { mutableStateOf(settings.resistanceWindowPercent.toString()) }
    var rejection by remember { mutableStateOf(settings.outlierRejection) }
    var tolerance by remember { mutableStateOf(settings.outlierTolerancePercent.toString()) }
    var noise by remember { mutableStateOf(settings.outlierNoiseMultiplier.toString()) }
    var confirmation by remember { mutableStateOf(settings.newLevelReadings.toString()) }
    fun use(value: CalibrationStreamSettings) {
        settle = value.settleSeconds.toString(); cadence = value.cadenceWindowRpm.toString()
        resistance = value.resistanceWindowPercent.toString(); rejection = value.outlierRejection
        tolerance = value.outlierTolerancePercent.toString(); noise = value.outlierNoiseMultiplier.toString()
        confirmation = value.newLevelReadings.toString()
    }
    val parsed = runCatching { CalibrationStreamSettings(settle.toInt(), cadence.toDouble(), resistance.toInt(),
        rejection, tolerance.toDouble(), noise.toDouble(), confirmation.toInt()) }.getOrNull()
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Capture settings", Modifier.testTag("stream-capture-settings-title")) },
        text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Larger windows tolerate more variation. Every pair still updates only its actual cell.", fontSize = 12.sp)
            Text("Calibration inputs use a factory cadence reference at the cell RPM when bike identity is known. Displayed pedal watts and saved originals stay raw.", fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton({ use(CalibrationStreamSettings()) }) { Text("Defaults") }
                TextButton({ use(CalibrationStreamSettings.MoreForgiving) }) { Text("More forgiving") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Reject isolated outliers", Modifier.padding(top = 10.dp))
                Switch(rejection, { rejection = it }, Modifier.semantics { contentDescription = "Reject isolated outliers" })
            }
            BoxWithConstraints {
                val columns = if (maxWidth >= 500.dp) 2 else 1
                val fields = listOf(
                    StreamSettingField("Settle time (seconds)", settle, { settle = it }, "0–15 seconds after a change", settle.toIntOrNull() in 0..15),
                    StreamSettingField("Cadence window (± rpm)", cadence, { cadence = it }, "0.5–15 rpm from the settled pace", cadence.toDoubleOrNull()?.let { it.isFinite() && it in .5..15.0 } == true),
                    StreamSettingField("Resistance window (± % cell)", resistance, { resistance = it }, "1–100% of one resistance column", resistance.toIntOrNull() in 1..100),
                    StreamSettingField("Outlier tolerance (percentage points)", tolerance, { tolerance = it }, "1–30 pp; 3 pp ≈ 15 W at 500 W", tolerance.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..30.0 } == true, rejection),
                    StreamSettingField("Recent noise allowance (×)", noise, { noise = it }, "1–10 × recent median deviation", noise.toDoubleOrNull()?.let { it.isFinite() && it in 1.0..10.0 } == true, rejection),
                    StreamSettingField("New level confirmation (readings)", confirmation, { confirmation = it }, "2–10 consistent shifted readings", confirmation.toIntOrNull() in 2..10, rejection))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fields.chunked(columns).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { field -> StreamSettingInput(field, Modifier.weight(1f)) }
                        if (row.size < columns) Spacer(Modifier.weight(1f))
                    } }
                }
            }
            Text("Saving restarts settling and keeps accepted data. Freshness, valid-power and duplicate-packet checks always remain on.", fontSize = 11.sp)
        } },
        confirmButton = { TextButton({ parsed?.let { onSave(it); onClose() } }, enabled = parsed != null) { Text("Save settings") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

private data class StreamSettingField(val label: String, val value: String, val change: (String) -> Unit,
    val hint: String, val valid: Boolean, val enabled: Boolean = true)

@Composable private fun StreamSettingInput(field: StreamSettingField, modifier: Modifier) {
    Column(modifier) {
        OutlinedTextField(field.value, field.change, Modifier.fillMaxWidth(), label = { Text(field.label, fontSize = 12.sp) },
            singleLine = true, enabled = field.enabled, isError = !field.valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text(field.hint, fontSize = 11.sp, lineHeight = 14.sp)
    }
}
