package com.spop.poverlay.calibration

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.roundToInt

/** Shared by the live example and slider values, independent of the device's number locale. */
internal fun captureSettingNumber(value: Double): String =
    String.format(Locale.US, "%.1f", value).removeSuffix(".0")

internal fun outlierWattExample(input: String): String {
    val percent = input.toDoubleOrNull()?.takeIf { it.isFinite() && it in 1.0..30.0 }
        ?: return "Enter 1–30 percentage points to see the watt equivalent."
    return "${captureSettingNumber(percent)} pp ≈ ±${captureSettingNumber(percent * 5)} W at 500 W raw bike power"
}

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
    val accent = MaterialTheme.colors.secondary
    val muted = MaterialTheme.colors.onSurface.copy(alpha = .7f)
    val shape = RoundedCornerShape(18.dp)
    AlertDialog(onDismissRequest = onClose,
        modifier = Modifier.widthIn(max = 940.dp).fillMaxWidth(.94f), shape = shape,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("TUNE YOUR CAPTURE", color = accent, fontSize = 10.sp, letterSpacing = 1.5.sp,
                    fontWeight = FontWeight.Bold)
                Text("Capture settings", Modifier.testTag("stream-capture-settings-title"), fontWeight = FontWeight.Bold)
                Text("Tap a number to type, or tap and drag its slider.", color = muted, fontSize = 12.sp)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = accent.copy(alpha = .07f), shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, accent.copy(alpha = .25f))) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Forgiving by default", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text("More room for natural riding variation.", color = muted, fontSize = 11.sp)
                        }
                        OutlinedButton({ use(CalibrationStreamSettings.Stricter) }) { Text("Stricter settings", fontSize = 12.sp) }
                        TextButton({ use(CalibrationStreamSettings()) }) { Text("Defaults", fontSize = 12.sp) }
                    }
                }
                CaptureSettingsSection("01", "Settle & collect", "Keep a steady pace; each pair updates only its actual cell.") {
                    StreamSettingFields(listOf(
                        StreamSettingField("settle", "Settle time (seconds)", settle, { settle = it },
                            "Wait after a change", 0.0, 15.0, 1.0),
                        StreamSettingField("cadence", "Cadence window (± rpm)", cadence, { cadence = it },
                            "From the settled pace", .5, 15.0, .5),
                        StreamSettingField("resistance", "Resistance window (± % cell)", resistance, { resistance = it },
                            "Percent of one resistance column", 1.0, 100.0, 1.0)))
                }
                CaptureSettingsSection("02", "Outlier protection", "Reject isolated spikes while letting a consistent new level settle in.") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Reject isolated outliers", Modifier.weight(1f), fontSize = 13.sp)
                        Switch(rejection, { rejection = it }, Modifier.semantics { contentDescription = "Reject isolated outliers" })
                    }
                    StreamSettingFields(listOf(
                        StreamSettingField("tolerance", "Outlier tolerance (percentage points)", tolerance, { tolerance = it },
                            outlierWattExample(tolerance), 1.0, 30.0, .5, rejection),
                        StreamSettingField("noise", "Recent noise allowance (×)", noise, { noise = it },
                            "Times the recent median deviation", 1.0, 10.0, .5, rejection),
                        StreamSettingField("confirmation", "New level confirmation (readings)", confirmation, { confirmation = it },
                            "Consistent shifted readings", 2.0, 10.0, 1.0, rejection)))
                }
                Text("Factory cadence normalization affects calibration calculations only. Displayed pedal watts and saved originals stay raw.",
                    fontSize = 11.sp, color = muted)
                Text("Saving restarts settling and keeps accepted data. Freshness, valid-power and duplicate-packet checks always remain on.",
                    fontSize = 11.sp, color = muted)
            }
        },
        confirmButton = { Button({ parsed?.let { onSave(it); onClose() } }, enabled = parsed != null) { Text("Save settings") } },
        dismissButton = { TextButton(onClose) { Text("Cancel") } })
}

@Composable
private fun CaptureSettingsSection(number: String, title: String, hint: String, content: @Composable () -> Unit) {
    val accent = MaterialTheme.colors.secondary
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colors.onSurface.copy(alpha = .025f),
        border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = .12f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = .12f)) {
                    Text(number, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), color = accent,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(hint, fontSize = 11.sp, color = MaterialTheme.colors.onSurface.copy(alpha = .7f))
            content()
        }
    }
}

private data class StreamSettingField(val key: String, val label: String, val value: String, val change: (String) -> Unit,
    val hint: String, val minimum: Double, val maximum: Double, val step: Double, val enabled: Boolean = true) {
    val number get() = value.toDoubleOrNull()?.takeIf { it.isFinite() }
    val valid get() = number?.let { it in minimum..maximum && (step < 1.0 || it == it.toInt().toDouble()) } == true
}

@Composable
private fun StreamSettingFields(fields: List<StreamSettingField>) {
    BoxWithConstraints {
        val columns = if (maxWidth >= 650.dp) 3 else if (maxWidth >= 440.dp) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            fields.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { field -> StreamSettingInput(field, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StreamSettingInput(field: StreamSettingField, modifier: Modifier) {
    Column(modifier) {
        OutlinedTextField(field.value, field.change, Modifier.fillMaxWidth(), label = { Text(field.label, fontSize = 11.sp) },
            singleLine = true, enabled = field.enabled, isError = !field.valid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Slider(value = (field.number ?: field.minimum).coerceIn(field.minimum, field.maximum).toFloat(),
            onValueChange = { raw ->
                val snapped = (field.minimum + ((raw - field.minimum) / field.step).roundToInt() * field.step)
                    .coerceIn(field.minimum, field.maximum)
                field.change(captureSettingNumber(snapped))
            },
            enabled = field.enabled, valueRange = field.minimum.toFloat()..field.maximum.toFloat(),
            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
            steps = ((field.maximum - field.minimum) / field.step).roundToInt() - 1,
            modifier = Modifier.fillMaxWidth().testTag("capture-slider-${field.key}")
                .semantics { contentDescription = "Adjust ${field.label}" })
        Text(field.hint, fontSize = 11.sp, lineHeight = 14.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = if (field.enabled) .7f else .38f),
            modifier = Modifier.testTag("capture-hint-${field.key}"))
    }
}
