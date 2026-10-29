package com.spop.poverlay.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BoardWritePanel(state: BoardWriteUiState, fitReady: Boolean,
    onPrepare: () -> Unit, onRestore: () -> Unit, onWrite: () -> Unit, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose(onClose) }
    Divider()
    Text("Sensor-board table", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Text(state.message, fontSize = 13.sp, color = Color(0xFFFBBF24))
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.active) {
        val cue = when {
            state.rpm != 0 -> "Stop pedaling"
            state.ad == null || state.target == null -> "Reading sensor…"
            state.ad < state.target -> "Turn knob right"
            state.ad > state.target -> "Turn knob left"
            else -> "Hold knob still"
        }
        Text(cue, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Point ${state.index ?: "—"} · ${state.ad ?: "—"} to ${state.target ?: "—"}", fontSize = 20.sp)
        Button(onWrite, enabled = state.canWrite && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Write this point & verify")
        }
        TextButton(onClose, enabled = !state.busy) { Text("Stop setup") }
    } else if (confirm) {
        Text("After riding: stop pedals, then follow the knob targets. Each point needs a tap to write. Original table is backed up.", fontSize = 14.sp)
        Text("Experimental: command behavior still needs hardware verification. Sensor access may be blocked by Affernet.", fontSize = 13.sp)
        Row {
            Button({ confirm = false; onPrepare() }, enabled = !state.busy) { Text("Check board access") }
            TextButton({ confirm = false }) { Text("Cancel") }
        }
    } else {
        OutlinedButton({ confirm = true }, enabled = fitReady && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Set up board write…") }
        if (state.backupAvailable) TextButton(onRestore, enabled = !state.busy) { Text("Restore original table…") }
    }
}
