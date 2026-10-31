package com.spop.poverlay.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun CalibrationSettingsDialog(settings: CalibrationCaptureSettings,
    onSave: (CalibrationCaptureSettings) -> Unit, onClose: () -> Unit) {
    var settle by remember { mutableStateOf(settings.settleSeconds.toString()) }
    var movement by remember { mutableStateOf(settings.resistanceMovementPercent.toString()) }
    val seconds = settle.toIntOrNull()?.takeIf { it in 0..15 }
    val percent = movement.toIntOrNull()?.takeIf { it in 1..100 }
    AlertDialog(onDismissRequest = onClose, title = { Text("Capture settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth()) { Text("Setting", Modifier.weight(1f)); Text("Value", Modifier.width(120.dp)) }
            Divider()
            Row {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Settle time")
                    Text("Ignore the first 0–15 seconds after a change.", fontSize = 12.sp)
                }
                OutlinedTextField(settle, { settle = it }, Modifier.width(120.dp), label = { Text("Seconds") },
                    singleLine = true, isError = seconds == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Max resistance movement")
                    Text("1–100% of one resistance-table step; minimum 3 raw sensor units.", fontSize = 12.sp)
                }
                OutlinedTextField(movement, { movement = it }, Modifier.width(120.dp), label = { Text("Percent") },
                    singleLine = true, isError = percent == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Text("Five settled readings are always required. Saving restarts the current hold and keeps saved efforts.", fontSize = 12.sp)
            TextButton({ settle = "3"; movement = "10" }) { Text("Restore defaults") }
        }
    }, confirmButton = {
        TextButton({ if (seconds != null && percent != null) { onSave(CalibrationCaptureSettings(seconds, percent)); onClose() } },
            enabled = seconds != null && percent != null) { Text("Save settings") }
    }, dismissButton = { TextButton(onClose) { Text("Cancel") } })
}
