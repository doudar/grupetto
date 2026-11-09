package com.spop.poverlay.openbikecontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun obcLinkLabel(link: ObcLink): String = when (link.phase) {
    ObcLink.Phase.Off -> link.detail ?: "Off"
    ObcLink.Phase.Searching -> link.detail ?: "Searching…"
    ObcLink.Phase.Connecting -> "Connecting to ${link.device ?: "remote"}"
    ObcLink.Phase.Connected -> "Connected · ${link.device ?: "remote"}"
    ObcLink.Phase.Unavailable -> link.detail ?: "Unavailable"
}

@Composable
internal fun OpenBikeControlSettings(enabled: Boolean, status: OpenBikeControlStatus, preview: Boolean, onEnabled: (Boolean) -> Unit) {
    // The switch follows the stored setting: the manager only follows it once the overlay runs.
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(enabled, role = Role.Switch, onValueChange = onEnabled),
            verticalAlignment = Alignment.CenterVertically) {
            Text("OpenBikeControl remotes", Modifier.weight(1f), fontSize = 16.sp)
            Switch(enabled, null)
        }
        Text("Shift with BikeControl or another OpenBikeControl remote. Grupetto looks for one on Wi-Fi and Bluetooth " +
            "and connects to the first it finds. In BikeControl, choose \"OpenBikeControl Compatible\" as the trainer app.",
            fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF9EAEC0))
        Spacer(Modifier.height(16.dp))
        when {
            preview -> Text("Remotes are off in emulation.", fontSize = 14.sp, color = Color(0xFF9EAEC0))
            enabled && !status.active -> Text("Starts with the overlay.", fontSize = 14.sp, color = Color(0xFF9EAEC0))
            enabled -> {
                LinkRow("Wi-Fi", status.network)
                LinkRow("Bluetooth", status.bluetooth)
                status.battery?.let { Detail("Remote battery", "$it%") }
                status.lastShift?.let { Detail("Last press", it) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Each press is one shift, the same as the on-screen shifters: ERG shifts change target watts; " +
            "Sim and Manual shifts change resistance. Shift and difficulty buttons both work.",
            fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF9EAEC0))
    }
}

@Composable
private fun LinkRow(name: String, link: ObcLink) =
    Detail(name, obcLinkLabel(link), if (link.phase == ObcLink.Phase.Connected) Color(0xFF6EE7B7) else Color(0xFF9EAEC0))

@Composable
private fun Detail(name: String, value: String, color: Color = Color(0xFF9EAEC0)) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, fontSize = 15.sp)
        Text(value, fontSize = 14.sp, color = color)
    }
}
