package com.spop.poverlay.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.sensor.heartrate.HeartRateDevice

/** Saved devices remain visible, with text as well as color communicating connection state. */
@Composable
internal fun SavedSensorList(
    devices: List<HeartRateDevice>, connectedAddress: String?, connectingAddress: String?,
    onConnect: (HeartRateDevice) -> Unit, onDisconnect: () -> Unit, onForget: (String) -> Unit
) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth()) {
        Text("Saved devices", fontWeight = FontWeight.SemiBold)
        Surface(Modifier.fillMaxWidth().padding(top = 4.dp),
            color = Color(0xFF111A24), border = BorderStroke(1.dp, Color(0xFF65758A))) {
            Column(Modifier.heightIn(max = 154.dp).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 4.dp)) {
                if (devices.isEmpty()) Text("No saved devices", Modifier.padding(vertical = 12.dp), color = Color(0xFFBAC6D3))
                devices.forEach { device ->
                    val connected = device.address == connectedAddress
                    val connecting = !connected && device.address == connectingAddress
                    val label = when { connected -> "Connected"; connecting -> "Connecting"; else -> "Not Connected" }
                    val color = when { connected -> Color(0xFF4ADE80); connecting -> Color(0xFFFBBF24); else -> Color(0xFFFB7185) }
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(device.name ?: device.address, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(device.address, color = Color(0xFF9EAEC0), fontSize = 12.sp)
                        Row {
                            if (connected || connecting) TextButton(onDisconnect) { Text(if (connecting) "Cancel" else "Disconnect") }
                            else TextButton({ onConnect(device) }, enabled = connectedAddress == null && connectingAddress == null) { Text("Connect") }
                            TextButton({ onForget(device.address) }) { Text("Forget") }
                        }
                    }
                    Divider(color = Color(0xFF36465A))
                }
            }
        }
        if (scroll.maxValue > 0) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, tint = if (scroll.value > 0) Color.White else Color.Gray)
                Text(if (scroll.value > 0) "Scroll up" else "Top", fontSize = 12.sp, color = Color(0xFFBAC6D3))
            }
            Row {
                Text(if (scroll.value < scroll.maxValue) "Scroll down" else "Bottom", fontSize = 12.sp, color = Color(0xFFBAC6D3))
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = if (scroll.value < scroll.maxValue) Color.White else Color.Gray)
            }
        }
    }
}
