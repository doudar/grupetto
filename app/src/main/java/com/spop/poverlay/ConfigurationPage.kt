package com.spop.poverlay

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import kotlin.math.max

private data class UiScale(
                val value: Float
) {
        fun sp(base: Float) = max(base * value, 16f).sp
        fun dp(base: Float) = (base * value).dp
}

@Composable
fun ConfigurationPage(viewModel: ConfigurationViewModel) {
    val permission by viewModel.showPermissionInfo
    val release by viewModel.latestRelease
    val boot by viewModel.autoStartOnBoot.collectAsStateWithLifecycle()
    val timer by viewModel.showTimerWhenMinimized.collectAsStateWithLifecycle()
    val ble by viewModel.bleTxEnabled.collectAsStateWithLifecycle()
    val dircon by viewModel.dirConEnabled.collectAsStateWithLifecycle()
    val ant by viewModel.antPlusTxEnabled.collectAsStateWithLifecycle()
    val running by viewModel.isOverlayRunning.collectAsStateWithLifecycle()
    val background by viewModel.backgroundLocationGranted.collectAsStateWithLifecycle()
    val hr by viewModel.hrConnectedDevice.collectAsStateWithLifecycle()
    val discovered by viewModel.hrDiscoveredDevices.collectAsStateWithLifecycle()
    val saved by viewModel.hrSavedDevices.collectAsStateWithLifecycle()
    val scanning by viewModel.hrIsScanning.collectAsStateWithLifecycle()
    val match by viewModel.hrMatchByName.collectAsStateWithLifecycle()
    val bike by viewModel.bikeControlState.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var developerTaps by remember { mutableStateOf(0) }
    var lastDeveloperTap by remember { mutableStateOf(0L) }
    Box(Modifier.fillMaxSize().padding(20.dp)) {
        if (permission) {
            Column(Modifier.align(Alignment.Center)) {
                PermissionPage(viewModel::onGrantPermissionClicked, UiScale(.7f))
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Grupetto", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(viewModel.emulatedModel?.let { "${it.label} emulation · Simulated data · Radios off" }
                            ?: "Your ride, connected", color = Color(0xFF9EAEC0), fontSize = 14.sp)
                    }
                    Button(viewModel::onStartServiceClicked, Modifier.heightIn(min = 48.dp)) {
                        Text(if (running) "Restart overlay" else "Start overlay")
                    }
                    TextButton(viewModel::onQuitClicked, Modifier.padding(start = 12.dp)) { Text("Quit") }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsTile("Overlay", Modifier.weight(1f).fillMaxHeight()) {
                        SettingSwitch("Start on boot", boot, viewModel::onAutoStartOnBootClicked)
                        SettingSwitch("Timer when minimized", timer, viewModel::onShowTimerWhenMinimizedClicked)
                        if (boot && !background) Text("Allow all-the-time location for HR at boot.", fontSize = 13.sp, color = Color(0xFFFFCC44))
                    }
                    SettingsTile("Connections", Modifier.weight(1f).fillMaxHeight()) {
                        Text(listOfNotNull(if (ble) "Bluetooth" else null, if (dircon) "Network" else null,
                            if (ant && viewModel.antPlusSupported) "ANT+" else null).joinToString(" · ").ifEmpty { "Broadcasting off" },
                            color = Color(0xFF9EAEC0))
                        Spacer(Modifier.weight(1f))
                        OutlinedButton({ dialog = "connections" }) { Text("Manage connections") }
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsTile("Heart rate", Modifier.weight(1f).fillMaxHeight()) {
                        Text(hr?.let { it.name ?: it.address } ?: "No monitor connected",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFF9EAEC0))
                        Spacer(Modifier.weight(1f))
                        OutlinedButton({ dialog = "heart" }) { Text("Monitors & zones") }
                    }
                    if (bike.connected) {
                        SettingsTile("Bike+ / CrossTrainer control", Modifier.weight(1f).fillMaxHeight()) {
                            Text(if (bike.mode == com.spop.poverlay.control.ControlMode.Erg) "ERG · ${bike.targetWatts} W"
                                else bike.mode.name, color = Color(0xFF6EE7B7))
                            Text(bike.message, fontSize = 13.sp, maxLines = 2)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton({ dialog = "bike" }) { Text("Modes & tuning") }
                                TextButton(viewModel::stopBikeControl) { Text("Manual") }
                            }
                        }
                    } else {
                        SettingsTile("About", Modifier.weight(1f).fillMaxHeight()) {
                            Text("Live metrics over your favorite training apps.", color = Color(0xFF9EAEC0))
                            Spacer(Modifier.weight(1f))
                            OutlinedButton({ dialog = "about" }) { Text("Version & updates") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Grupetto ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = Color(0xFF9EAEC0),
                        modifier = Modifier.weight(1f).clickable {
                            val now = android.os.SystemClock.elapsedRealtime()
                            developerTaps = if (now - lastDeveloperTap > 2000) 1 else developerTaps + 1
                            lastDeveloperTap = now
                            if (developerTaps >= 5) { developerTaps = 0; dialog = "developer" }
                        }.padding(vertical = 8.dp))
                    TextButton({ dialog = "about" }, Modifier.height(32.dp), contentPadding = PaddingValues(0.dp)) { Text("About & updates", fontSize = 12.sp) }
                }
            }
        }
    }
    when (dialog) {
        "developer" -> SettingsDialog("Developer · Model emulation", { dialog = null }) {
            Text("Emulation uses simulated sensors and motor control. Broadcasts are disabled. Selecting a model restarts Grupetto.",
                fontSize = 14.sp, color = Color(0xFF9EAEC0))
            TextButton({ viewModel.emulateModel(null) }, Modifier.fillMaxWidth()) {
                Text(if (viewModel.emulatedModel == null) "✓ Detected hardware" else "Use detected hardware")
            }
            com.spop.poverlay.sensor.interfaces.EmulatedModel.values().forEach { model ->
                TextButton({ viewModel.emulateModel(model) }, Modifier.fillMaxWidth()) {
                    Text((if (viewModel.emulatedModel == model) "✓ " else "") + model.label)
                }
            }
        }
        "connections" -> SettingsDialog("Connections", { dialog = null }) {
            SettingSwitch("Bluetooth FTMS", ble, viewModel::onBleTxEnabledClicked)
            SettingSwitch("Network / DirCon", dircon, viewModel::onDirConEnabledClicked)
            Text("Bluetooth name: Grupetto", fontSize = 14.sp, color = Color(0xFF9EAEC0))
            if (viewModel.antPlusSupported) {
                Divider(Modifier.padding(vertical = 12.dp))
                SettingSwitch("ANT+ broadcast", ant, viewModel::onAntPlusTxEnabledClicked)
                Text("ANT+ sensor IDs: power 1 · speed/cadence 2 · HR 3", fontSize = 14.sp)
            }
        }
        "bike" -> if (bike.connected) SettingsDialog("Bike+ / CrossTrainer", { dialog = null }) {
            var watts by remember { mutableStateOf(bike.targetWatts.toFloat()) }
            Text("ERG target · ${watts.toInt()} W")
            Slider(watts, { watts = it }, valueRange = 25f..1000f, steps = 194)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button({ viewModel.startErg(watts.toInt()) }) { Text("Start ERG") }
                OutlinedButton(viewModel::startSimulation) { Text("Start Sim") }
                TextButton(viewModel::stopBikeControl) { Text("Manual") }
            }
            Divider(Modifier.padding(vertical = 12.dp))
            Text("Resistance per shift · ${bike.shiftSize} points")
            Slider(bike.shiftSize.toFloat(), { viewModel.setBikeTuning(it.toInt(), bike.gain) },
                valueRange = 1f..10f, steps = 8)
            Text("Proportional gain · ${"%.3f".format(java.util.Locale.US, bike.gain)}")
            Slider(bike.gain, { viewModel.setBikeTuning(bike.shiftSize, it) },
                valueRange = .001f..0.030f, steps = 28)
            Text("Higher gain reacts faster in ERG. Sim shifters adjust resistance on either side of the overlay.",
                fontSize = 13.sp, color = Color(0xFF9EAEC0))
            Text(bike.message, fontSize = 13.sp)
        }
        "heart" -> HeartRateManagerDialog(hr, discovered, saved, scanning, match,
            viewModel::startHeartRateDiscovery, viewModel::stopHeartRateDiscovery,
            viewModel::connectHeartRateDevice, viewModel::disconnectHeartRateDevice,
            viewModel::forgetHeartRateDevice, viewModel::setHrMatchByName) { dialog = null }
        "about" -> SettingsDialog("About Grupetto", { dialog = null }) {
            Text("Version ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}")
            Text("Device: ${Build.MODEL}", fontSize = 14.sp)
            Spacer(Modifier.height(16.dp))
            val current = release
            Text(current?.let { if (it.isCurrentlyInstalled) "You're up to date" else "Available: ${it.friendlyName}" }
                ?: "Couldn't check for updates")
            if (current != null) TextButton({ viewModel.onClickedRelease(current) }) { Text("View release") }
            Text("Bike+ ERG work based on dwj300's contribution (PR #50).", fontSize = 14.sp)
        }
    }
}

@Composable
private fun SettingsTile(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        backgroundColor = Color(0xFF1B2430), elevation = 0.dp) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingsDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 660.dp).fillMaxWidth(.9f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = Color(0xFF1B2430)) {
            Column(Modifier.padding(24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    TextButton(onDismiss) { Text("Done") }
                }
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

@Composable
private fun PermissionPage(onClickedGrantPermission: () -> Unit, uiScale: UiScale) {
    Text(
            text = "Grupetto Needs Permission To Draw Over Other Apps",
            fontSize = uiScale.sp(40f),
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold
    )
    Text(
            text = "It uses this permission to draw an overlay with your bike's sensor data",
            fontSize = uiScale.sp(20f),
            fontWeight = FontWeight.Normal
    )
    Spacer(modifier = Modifier.height(uiScale.dp(10f)))
    Button(onClick = onClickedGrantPermission) { Text(text = "Grant Permission") }
}

@Composable
private fun HeartRateManagerDialog(
    connectedDevice: HeartRateDevice?,
    discoveredDevices: List<HeartRateDevice>,
    savedDevices: List<HeartRateDevice>,
    isScanning: Boolean,
    matchByName: Boolean,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onConnectDevice: (HeartRateDevice) -> Unit,
    onDisconnectConnectedDevice: () -> Unit,
    onForgetDevice: (String) -> Unit,
    onSetMatchByName: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    val bpm by HeartRateManager.heartRate.collectAsStateWithLifecycle()
    val zone12 by HeartRateManager.zone12.collectAsStateWithLifecycle()
    val zone23 by HeartRateManager.zone23.collectAsStateWithLifecycle()
    val zone34 by HeartRateManager.zone34.collectAsStateWithLifecycle()
    val zone45 by HeartRateManager.zone45.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        onStartDiscovery()
        HeartRateManager.setManaging(true)
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onStopDiscovery(); HeartRateManager.setManaging(false) }
    }
    SettingsDialog("Heart rate", onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(connectedDevice?.name ?: connectedDevice?.address ?: "No monitor connected",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${bpm ?: "—"} bpm", color = Color(0xFFFB7185))
            }
            if (connectedDevice != null) {
                TextButton(onDisconnectConnectedDevice) { Text("Disconnect") }
                TextButton({ onForgetDevice(connectedDevice.address) }) { Text("Forget") }
            }
        }
        Spacer(Modifier.height(8.dp))
        TabRow(tab, backgroundColor = Color.Transparent) {
            listOf("Discover", "Saved", "Zones").forEachIndexed { index, label ->
                Tab(tab == index, { tab = index; page = 0 }, text = { Text(label) })
            }
        }
        Spacer(Modifier.height(12.dp))
        if (tab == 2) {
            Text("Zone boundaries (bpm)", fontWeight = FontWeight.SemiBold)
            Text("Leave a boundary blank to use the default.", fontSize = 13.sp)
            val stored = listOf(zone12, zone23, zone34, zone45)
            val values = remember(zone12, zone23, zone34, zone45) {
                androidx.compose.runtime.mutableStateListOf(*stored.map { it?.toString() ?: "" }.toTypedArray())
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEachIndexed { index, value ->
                    OutlinedTextField(value, { input ->
                        values[index] = input.filter(Char::isDigit).take(3)
                        HeartRateManager.setHeartRateZones(values[0].toIntOrNull(), values[1].toIntOrNull(),
                            values[2].toIntOrNull(), values[3].toIntOrNull())
                    }, label = { Text("${index + 1} → ${index + 2}") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                }
            }
        } else {
            val devices = (if (tab == 0) discoveredDevices else savedDevices)
                .filter { it.address != connectedDevice?.address }
            val pages = maxOf(1, (devices.size + 2) / 3)
            val current = page.coerceAtMost(pages - 1)
            Column(Modifier.height(180.dp)) {
                if (devices.isEmpty()) Text(if (tab == 0 && isScanning) "Scanning for monitors…" else "No monitors")
                devices.drop(current * 3).take(3).forEach { device ->
                    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name ?: "Unknown monitor", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(device.address, fontSize = 12.sp, color = Color(0xFF9EAEC0))
                        }
                        TextButton({ onConnectDevice(device) }) { Text("Connect") }
                        if (tab == 1) TextButton({ onForgetDevice(device.address) }) { Text("Forget") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ page = current - 1 }, enabled = current > 0) { Text("Previous") }
                Text("${current + 1} / $pages", fontSize = 13.sp)
                TextButton({ page = current + 1 }, enabled = current + 1 < pages) { Text("Next") }
            }
        }
        Divider()
        SettingSwitch("Reconnect by name", matchByName, onSetMatchByName)
    }
}
