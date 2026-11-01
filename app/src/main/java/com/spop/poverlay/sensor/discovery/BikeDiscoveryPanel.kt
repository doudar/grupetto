package com.spop.poverlay.sensor.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BikeDiscoveryPanel(state: BikeDiscoveryState, connection: String, preview: Boolean,
    onScan: () -> Unit, onCancel: () -> Unit, onUse: (BikeProbeResult) -> Unit,
    onAutomatic: () -> Unit, onExport: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TabRow(tab, backgroundColor = Color.Transparent) {
            listOf("Overview", "Services", "Notes").forEachIndexed { index, label ->
                Tab(tab == index, { tab = index; page = 0 }, text = { Text(label) })
            }
        }
        if (tab == 0) {
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
        }
        if (tab == 1) {
        val results = state.results.flatMap { result -> result.outcome.chunked(600).ifEmpty { listOf("") }.map { result to it } }
        val current = page.coerceIn(0, (results.size - 1).coerceAtLeast(0))
        if (results.isEmpty()) Text("No results yet. Use Discover bike services on Overview.")
        results.getOrNull(current)?.let { (result, outcome) ->
            Divider()
            Text(result.protocol?.label ?: "Unrecognized service", fontSize = 15.sp)
            Text(result.endpoint.component, fontSize = 12.sp)
            Text(outcome, fontSize = 13.sp)
            result.sample?.let {
                Text("${it.watts} W · ${it.rpm} rpm · Resistance ${it.resistance}", fontSize = 14.sp, color = Color(0xFF6EE7B7))
            }
            if (result.profile != null) Button({ onUse(result) }, enabled = !preview && !state.scanning) {
                Text("Use this service & restart")
            }
        }
        DiscoveryPages(current, results.size) { page = it }
        }
        if (tab == 2) {
            val notes = state.notes.joinToString("\n\n").chunked(600)
            val current = page.coerceIn(0, (notes.size - 1).coerceAtLeast(0))
            Text(notes.getOrNull(current) ?: "No additional notes.", fontSize = 13.sp, color = Color(0xFF9EAEC0))
            DiscoveryPages(current, notes.size) { page = it }
        }
    }
}

@Composable
private fun DiscoveryPages(page: Int, count: Int, onPage: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ onPage(page - 1) }, enabled = page > 0) { Text("Previous") }
        Text("${page + 1} / ${count.coerceAtLeast(1)}", Modifier.padding(12.dp))
        TextButton({ onPage(page + 1) }, enabled = page + 1 < count) { Text("Next") }
    }
}
