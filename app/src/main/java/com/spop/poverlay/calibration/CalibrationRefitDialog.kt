package com.spop.poverlay.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/** Preview is read-only. Apply and undo are explicit and checked against the saved snapshot. */
@Composable
internal fun CalibrationRefitDialog(recording: Boolean,
    onPreview: suspend () -> CalibrationRefitPreview,
    onApply: suspend (CalibrationRefitPreview) -> Unit,
    onUndo: suspend (CalibrationRefitPreview) -> Unit,
    onClose: () -> Unit) {
    var preview by remember { mutableStateOf<CalibrationRefitPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var applied by remember { mutableStateOf(false) }
    var undone by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun rebuild() {
        busy = true; error = null; preview = null; undone = false
        try { preview = onPreview() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not prepare the refit preview." }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { if (!recording) rebuild() }
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, title = { Text("Refit saved data") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Optional: fit each populated cell to its original saved pedal measurements within the -35% to +35% correction limits. Where a factory cadence reference is available, fitting uses paired inputs normalized to that cell's RPM. Displayed errors still use original watts.")
                Text("Apply replaces recorded or manual values only in populated cells with original pedal evidence. Other stored cells are unchanged. Empty-cell fallback corrections may change.")
                Text("An active table changes live compensation when applied. An inactive table stays inactive. Saved measurements are retained; the fit may still have nonzero error.", fontSize = 12.sp)
                if (recording) Text("Pause recording before previewing, applying, or undoing a refit.", color = MaterialTheme.colors.error)
                if (busy) Text("Working...")
                preview?.result?.let { result ->
                    Text("${result.before.count} retained observations and legacy effort aggregates")
                    RefitMetric("Mean absolute error", result.before.meanAbsoluteError, result.after.meanAbsoluteError)
                    RefitMetric("Signed bias", result.before.meanSignedError, result.after.meanSignedError)
                    RefitMetric("RMSE", result.before.rmse, result.after.rmse)
                    Text("Current to fitted, in watts. Signed bias is corrected minus pedals.", fontSize = 12.sp)
                    Text("${result.fittedCells} fitted cells · ${result.cellsAtBounds} at correction limits", fontSize = 12.sp)
                }
                if (applied) Text("Refit applied. Undo is available here while this saved table remains unchanged.")
                if (undone) Text("Refit undone. The previous saved table was restored.")
                error?.let { Text(it, color = MaterialTheme.colors.error) }
                if (!applied && !busy && (error != null || preview == null || undone)) {
                    TextButton({
                        if (!busy && !recording) {
                            busy = true
                            scope.launch { rebuild() }
                        }
                    }, enabled = !recording) { Text("Rebuild preview") }
                }
            }
        }, confirmButton = {
            if (applied) {
                TextButton({
                    if (busy || recording) return@TextButton
                    val expected = preview ?: return@TextButton
                    busy = true; error = null
                    scope.launch {
                        try { onUndo(expected); applied = false; undone = true; preview = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "Could not undo the refit." }
                        finally { busy = false }
                    }
                }, enabled = !busy && !recording) { Text("Undo refit") }
            } else if (!undone) {
                TextButton({
                    if (busy || recording) return@TextButton
                    val expected = preview ?: return@TextButton
                    busy = true; error = null
                    scope.launch {
                        try { onApply(expected); applied = true }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            error = e.message ?: "Could not apply the refit. Rebuild the preview before trying again."
                            preview = null
                        }
                        finally { busy = false }
                    }
                }, enabled = preview != null && !busy && !recording) { Text("Apply refit") }
            }
        }, dismissButton = {
            TextButton({ if (!busy) onClose() }, enabled = !busy) { Text(if (applied || undone) "Close" else "Cancel") }
        })
}

@Composable
private fun RefitMetric(label: String, before: Double, after: Double) {
    Text(String.format(Locale.US, "%s: %.1f to %.1f W", label, before, after), fontSize = 14.sp)
}
