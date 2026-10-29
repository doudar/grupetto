package com.spop.poverlay.sensor.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BikeDiscoveryPanel(state: BikeDiscoveryState, connection: String, preview: Boolean,
    onScan: () -> Unit, onCancel: () -> Unit, onUse: (BikeProbeResult) -> Unit,
    onAutomatic: () -> Unit, onExport: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Find built-in bike sensors when automatic detection cannot connect.", fontSize = 15.sp)
        if (preview) Text("Exit model emulation to discover real services.", color = Color(0xFFFBBF24))
        state.selected?.let {
            Text("Saved service: ${it.endpoint.component}", fontSize = 13.sp)
            Text(connection, fontSize = 13.sp, color = Color(0xFF6EE7B7))
            OutlinedButton(onAutomatic, enabled = !preview && !state.scanning) { Text("Use automatic selection & restart") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onScan, enabled = !preview && !state.scanning) { Text("Discover bike services") }
            if (state.scanning) TextButton(onCancel) { Text("Cancel") }
            if (state.reportAvailable) OutlinedButton(onExport, enabled = !state.scanning) { Text("Export report") }
        }
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(state.message, fontSize = 14.sp)
        if (state.serviceCount > 0) Text("${state.serviceCount} services inventoried. Unknown and inaccessible services are included in the saved report.", fontSize = 13.sp)
        Text("Using a discovered service restarts Grupetto and provides bike readings only. Trainer control and calibration collection use automatic device selection.", fontSize = 13.sp, color = Color(0xFF9EAEC0))
        state.results.forEach { result ->
            Divider()
            Text(result.protocol?.label ?: "Unrecognized service", fontSize = 15.sp)
            Text(result.endpoint.component, fontSize = 12.sp)
            Text(result.outcome, fontSize = 13.sp)
            result.sample?.let {
                Text("${it.watts} W · ${it.rpm} rpm · Resistance ${it.resistance}", fontSize = 14.sp, color = Color(0xFF6EE7B7))
            }
            if (result.profile != null) Button({ onUse(result) }, enabled = !preview && !state.scanning) {
                Text("Use this service & restart")
            }
        }
        if (state.notes.isNotEmpty()) {
            Divider()
            state.notes.take(5).forEach { Text(it, fontSize = 12.sp, color = Color(0xFF9EAEC0)) }
            if (state.notes.size > 5) Text("Additional details are in the report.", fontSize = 12.sp)
        }
    }
}
