package com.spop.poverlay.calibration

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CalibrationFileControls(hasData: Boolean, canLoad: Boolean,
    export: suspend () -> String, load: suspend (CalibrationBackup) -> CalibrationLoadResult) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<CalibrationBackup?>(null) }
    var loadResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    val currentExport by rememberUpdatedState(export)
    val currentLoad by rememberUpdatedState(load)
    fun filename() = "grupetto-calibration-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
    fun runOperation(reportLoad: Boolean = false, action: suspend () -> Unit) {
        busy = true
        message = null
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = "Could not complete action: ${e.localizedMessage ?: "please try again"}"
                if (reportLoad) loadResult = "Table not loaded" to
                    (e.localizedMessage ?: "Please try again.")
            }
            finally { busy = false }
        }
    }
    fun openPicker(action: () -> Unit) {
        busy = true
        message = null
        try { action() }
        catch (_: Exception) { busy = false; message = "No file picker is available on this device." }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) busy = false else runOperation {
            val text = currentExport()
            withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("The selected file could not be opened for writing.")
                stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            }
            message = "Calibration backup saved."
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) busy = false else runOperation(reportLoad = true) {
            pending = withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: error("The selected file could not be opened.")
                try { stream.use(CalibrationBackup::read) }
                catch (e: IllegalArgumentException) { throw e }
                catch (e: Exception) { throw IllegalArgumentException("Invalid calibration backup. Choose a Grupetto JSON backup.", e) }
            }
        }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ openPicker { save.launch(filename()) } }, Modifier.weight(1f).heightIn(min = 48.dp),
                enabled = hasData && !busy) { Text("Save") }
            OutlinedButton({ openPicker { open.launch(arrayOf("*/*")) } }, Modifier.weight(1f).heightIn(min = 48.dp),
                enabled = canLoad && !busy) { Text("Load") }
            OutlinedButton({ runOperation {
                val text = currentExport()
                val file = withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "calibration-shares").apply { mkdirs() }
                    // Use a distinct attachment so later shares cannot change an earlier recipient's file.
                    File.createTempFile("grupetto-calibration-", ".json", directory).apply { writeText(text, Charsets.UTF_8) }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.calibration-files", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Calibration backup", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, "Share calibration table"))
            } }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = hasData && !busy) { Text("Share") }
        }
        Text(if (canLoad) "Backups include ride efforts, bike ID and power meter ID."
            else "Pause capture to load a backup. Save and Share remain available.", fontSize = 12.sp)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, fontSize = 14.sp) }
    }
    pending?.let { backup ->
        val capture = (backup.draft ?: backup.saved)!!.capture
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Load calibration backup?") },
            text = { Text("Bike: ${capture.frameSerial}\nPower meter: ${capture.meterAddress}\n" +
                "${backup.draft?.capture?.samples?.size ?: 0} draft efforts · ${backup.saved?.capture?.samples?.size ?: 0} saved table efforts\n\n" +
                "This replaces your current Grupetto tables. Correction will be off until you activate it. The bike's own table is unchanged.") },
            confirmButton = { TextButton({ pending = null; runOperation(reportLoad = true) {
                currentLoad(backup)
                message = "Backup loaded. Correction is off."
                loadResult = "Table loaded · correction off" to
                    "Use Activate to apply a ready app correction. Loading alone does not change the bike's onboard calibration."
            } }, enabled = canLoad && !busy) { Text("Load table") } },
            dismissButton = { TextButton({ pending = null }) { Text("Cancel") } })
    }
    loadResult?.let { (title, detail) ->
        AlertDialog(onDismissRequest = { loadResult = null }, title = { Text(title) },
            text = { Text(detail) },
            confirmButton = { TextButton({ loadResult = null }) { Text("OK") } })
    }
}
