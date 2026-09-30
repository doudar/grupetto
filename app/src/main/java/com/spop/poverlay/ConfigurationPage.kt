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
    val watts by viewModel.livePower.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val cadence by viewModel.liveCadence.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val resistance by viewModel.liveResistance.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val speed by viewModel.liveSpeed.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val incline by viewModel.liveIncline.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val bpm by HeartRateManager.heartRate.collectAsStateWithLifecycle()
    val meter by viewModel.powerMeterDevice.collectAsStateWithLifecycle()
    val externalWatts by viewModel.powerMeterReading.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    var developerTaps by remember { mutableStateOf(0) }
    var lastDeveloperTap by remember { mutableStateOf(0L) }
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        if (permission) {
            Column(Modifier.align(Alignment.Center)) {
                PermissionPage(viewModel::onGrantPermissionClicked, UiScale(.7f))
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Grupetto", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("  /  RIDE CONSOLE", color = Color(0xFF34D399), fontSize = 11.sp, letterSpacing = 1.sp)
                            bike.externalControl?.let {
                                Spacer(Modifier.width(12.dp))
                                ExternalControlFlag(it)
                            }
                        }
                        Text(viewModel.emulatedModel?.let { "${it.label} emulation · Simulated data · Radios off" }
                            ?: "Your ride, connected", color = Color(0xFF9EAEC0), fontSize = 14.sp)
                    }
                    Button(viewModel::onStartServiceClicked, Modifier.heightIn(min = 48.dp)) {
                        Text(if (running) "Restart overlay" else "Start overlay")
                    }
                    TextButton(viewModel::onQuitClicked, Modifier.padding(start = 12.dp)) { Text("Quit") }
                }
                Row(Modifier.fillMaxWidth().height(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.isTread) {
                        LiveMetric("SPEED", displayMetric(speed, 1), "mph", Color(0xFF60A5FA), Modifier.weight(1f))
                        LiveMetric("INCLINE", displayMetric(incline, 1), "%", Color(0xFF34D399), Modifier.weight(1f))
                    } else {
                        LiveMetric("POWER", displayMetric(watts), "W", Color(0xFFFBBF24), Modifier.weight(1f))
                        LiveMetric("CADENCE", displayMetric(cadence), "rpm", Color(0xFF34D399), Modifier.weight(1f))
                        LiveMetric("RESISTANCE", displayMetric(resistance), "%", Color(0xFF60A5FA), Modifier.weight(1f))
                    }
                    LiveMetric("HEART RATE", bpm?.toString() ?: "—", "bpm", Color(0xFFFB7185), Modifier.weight(1f))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsTile(if (running) "Overlay · Running" else "Overlay · Ready", Modifier.weight(1f).fillMaxHeight(), Color(0xFF34D399)) {
                        SettingSwitch("Start on boot", boot, viewModel::onAutoStartOnBootClicked)
                        SettingSwitch("Timer when minimized", timer, viewModel::onShowTimerWhenMinimizedClicked)
                    }
                    SettingsTile("Broadcast connections", Modifier.weight(1f).fillMaxHeight(), Color(0xFF60A5FA)) {
                        ConnectionSummary("Bluetooth", if (viewModel.isPreview) "Preview" else if (ble) "On · Grupetto" else "Off", ble)
                        ConnectionSummary("Network", if (viewModel.isPreview) "Preview" else if (dircon) "On · DirCon" else "Off", dircon)
                        if (viewModel.antPlusSupported) ConnectionSummary("ANT+", if (viewModel.isPreview) "Preview" else if (ant) "On · IDs 1 / 2 / 3" else "Off", ant)
                        Spacer(Modifier.weight(1f))
                        TileActionButton("Manage connections", { dialog = "connections" })
                    }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsTile("External Sensors", Modifier.weight(1f).fillMaxHeight(), Color(0xFFFB7185)) {
                        SensorSummary("Heart rate", hr?.let { it.name ?: it.address } ?: "Not connected",
                            bpm?.let { "$it bpm" } ?: "—", Color(0xFFFB7185))
                        SensorSummary("Power meter", meter?.let { it.name ?: it.address } ?: "Using built-in power",
                            externalWatts?.let { "${it.watts.coerceAtLeast(0)} W" } ?: "—", Color(0xFFFBBF24))
                        Spacer(Modifier.weight(1f))
                        TileActionButton("Pair & manage sensors", { dialog = "sensors" })
                    }
                    if (bike.connected) {
                        SettingsTile("Trainer control", Modifier.weight(1f).fillMaxHeight(), Color(0xFFFBBF24)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(when (bike.mode) {
                                    com.spop.poverlay.control.ControlMode.Erg -> "ERG · ${bike.targetWatts} W"
                                    com.spop.poverlay.control.ControlMode.Simulation -> "Sim · ${"%.1f".format(java.util.Locale.US, bike.targetIncline)}%"
                                    else -> "Manual · ${bike.targetResistance}"
                                }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6EE7B7))
                                Spacer(Modifier.weight(1f))
                                Text(if (externalWatts != null) "EXTERNAL POWER" else "BUILT-IN POWER", fontSize = 10.sp, color = Color(0xFF9EAEC0))
                            }
                            Text(when (bike.mode) {
                                com.spop.poverlay.control.ControlMode.Erg -> "Shift ${bike.wattsPerShift} W   ·   Gain ${"%.3f".format(java.util.Locale.US, bike.gain)}"
                                com.spop.poverlay.control.ControlMode.Simulation -> "Shift ${bike.shiftSize} pts   ·   ${"%.1f".format(java.util.Locale.US, bike.inclineSensitivity)} pts / 1% incline"
                                else -> "Shift ${bike.shiftSize} pts"
                            },
                                fontSize = 13.sp, lineHeight = 17.sp, color = Color(0xFF9EAEC0))
                            Text(bike.message, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.weight(1f))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TileActionButton("Modes & tuning", { dialog = "bike" }, Modifier.weight(1f))
                                OutlinedButton(viewModel::stopBikeControl, modifier = Modifier.height(48.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF52728F)),
                                    colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Color.Transparent, contentColor = Color(0xFFE6F3FF))) {
                                    Text("Manual", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    } else {
                        SettingsTile("Device & app", Modifier.weight(1f).fillMaxHeight(), Color(0xFFA78BFA)) {
                            Text(if (viewModel.isTread) "Tread · Speed & incline" else "Bike · Live telemetry", fontSize = 18.sp, lineHeight = 22.sp)
                            Text("Model ${Build.MODEL}   ·   Android ${Build.VERSION.RELEASE}", fontSize = 13.sp, lineHeight = 17.sp, color = Color(0xFF9EAEC0))
                            Text(if (externalWatts != null) "Power source: external meter" else "Power source: built-in sensors", fontSize = 13.sp, lineHeight = 17.sp)
                            Spacer(Modifier.weight(1f))
                            TileActionButton("Version & updates", { dialog = "about" })
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
                    TextButton({ dialog = "about" }, Modifier.height(32.dp), contentPadding = PaddingValues(0.dp)) {
                        Text(if (release?.isCurrentlyInstalled == false) "New Version Available" else "About & updates", fontSize = 12.sp)
                    }
                }
            }
        }
    }
    when (dialog) {
        "sensors" -> SettingsDialog("External Sensors", { dialog = null }) {
            SensorSummary("Heart rate", hr?.let { it.name ?: it.address } ?: "Pair a heart-rate monitor",
                bpm?.let { "$it bpm" } ?: "—", Color(0xFFFB7185))
            OutlinedButton({ dialog = "heart" }, Modifier.fillMaxWidth()) { Text("Heart-rate monitors & zones") }
            Divider(Modifier.padding(vertical = 12.dp))
            SensorSummary("Power meter", meter?.let { it.name ?: it.address } ?: "Use an external Cycling Power sensor",
                externalWatts?.let { "${it.watts.coerceAtLeast(0)} W" } ?: "—", Color(0xFFFBBF24))
            OutlinedButton({ dialog = "power" }, Modifier.fillMaxWidth()) { Text("Pair & manage power meters") }
            Text("External watts are used for display, broadcasting, and ERG. Changing or losing the source stops ERG until restarted.",
                fontSize = 13.sp, color = Color(0xFF9EAEC0))
        }
        "power" -> PowerMeterDialog(viewModel) { dialog = null }
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
            if (boot && !background) Text("Allow location all the time in Android settings for sensor reconnection after boot.",
                fontSize = 12.sp, color = Color(0xFFFBBF24))
            if (viewModel.antPlusSupported) {
                Divider(Modifier.padding(vertical = 12.dp))
                SettingSwitch("ANT+ broadcast", ant, viewModel::onAntPlusTxEnabledClicked)
                Text("ANT+ sensor IDs: power 1 · speed/cadence 2 · HR 3", fontSize = 14.sp)
            }
        }
        "bike" -> if (bike.connected) SettingsDialog("Bike+ / CrossTrainer", { dialog = null }) {
            TrainerControls(bike, viewModel::startErg, viewModel::startSimulation,
                viewModel::stopBikeControl, viewModel::setBikeResistance, viewModel::setBikeTuning)
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
private fun SettingsTile(title: String, modifier: Modifier, accent: Color = Color(0xFF34D399), content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        backgroundColor = Color(0xFF19232F), border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .25f)), elevation = 0.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(title, fontSize = 17.sp, lineHeight = 22.sp, color = accent, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun TileActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    Button(onClick, modifier.height(48.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF52728F)),
        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2A4056), contentColor = Color(0xFFE6F3FF)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        Switch(checked, onChange)
    }
}

private fun displayMetric(value: Float, decimals: Int = 0): String =
    if (value.isFinite()) String.format(java.util.Locale.US, "%.$decimals" + "f", value) else "—"

@Composable
private fun LiveMetric(label: String, value: String, unit: String, accent: Color, modifier: Modifier) {
    Surface(modifier.fillMaxHeight(), color = Color(0xFF111C27),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text(label, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp, color = Color(0xFF9EAEC0))
            Row {
                Text(value, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                    color = accent, modifier = Modifier.alignByBaseline())
                Text(" $unit", fontSize = 12.sp, lineHeight = 16.sp, color = Color(0xFF9EAEC0),
                    modifier = Modifier.alignByBaseline())
            }
        }
    }
}

@Composable
private fun ConnectionSummary(name: String, detail: String, enabled: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFFBAC6D3))
        Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = if (enabled) Color(0xFF6EE7B7) else Color(0xFF718096))
    }
}

@Composable
private fun SensorSummary(label: String, name: String, value: String, accent: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, lineHeight = 16.sp, color = Color(0xFFBAC6D3))
            Text(name, fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF8796A8), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PowerMeterDialog(viewModel: ConfigurationViewModel, onDismiss: () -> Unit) {
    val connected by viewModel.powerMeterDevice.collectAsStateWithLifecycle()
    val reading by viewModel.powerMeterReading.collectAsStateWithLifecycle()
    val status by viewModel.powerMeterStatus.collectAsStateWithLifecycle()
    val discovered by viewModel.powerMeterDiscovered.collectAsStateWithLifecycle()
    val saved by viewModel.powerMeterSaved.collectAsStateWithLifecycle()
    val scanning by viewModel.powerMeterScanning.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        viewModel.managePowerMeters(true)
        onDispose { viewModel.managePowerMeters(false) }
    }
    SettingsDialog("External Sensors · Power", onDismiss) {
        SensorSummary("Power meter",
            connected?.let { it.name ?: it.address } ?: status,
            "${reading?.watts?.coerceAtLeast(0) ?: "—"} W", Color(0xFFFBBF24))
        if (connected != null) Row {
            TextButton(viewModel::disconnectPowerMeter) { Text("Disconnect") }
            TextButton({ connected?.let { viewModel.forgetPowerMeter(it.address) } }) { Text("Forget") }
        }
        Text(if (viewModel.isPreview) "Model emulation: pairing is disabled" else status,
            fontSize = 12.sp, color = Color(0xFF9EAEC0))
        TabRow(tab, backgroundColor = Color.Transparent) {
            listOf("Discover", "Saved").forEachIndexed { index, name ->
                Tab(tab == index, { tab = index; page = 0 }, text = { Text(name) })
            }
        }
        val devices = (if (tab == 0) discovered else saved).filter { it.address != connected?.address }
        val pages = maxOf(1, (devices.size + 1) / 2)
        val current = page.coerceAtMost(pages - 1)
        Column(Modifier.height(128.dp).padding(top = 8.dp)) {
            if (devices.isEmpty()) Text(if (scanning) "Scanning… Wake your power meter by pedaling." else "No power meters found")
            devices.drop(current * 2).take(2).forEach { device ->
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name ?: "Power meter", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.address, fontSize = 12.sp, color = Color(0xFF9EAEC0))
                    }
                    TextButton({ viewModel.connectPowerMeter(device) }) { Text("Connect") }
                    if (tab == 1) TextButton({ viewModel.forgetPowerMeter(device.address) }) { Text("Forget") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ page = current - 1 }, enabled = current > 0) { Text("Previous") }
            Text("${current + 1} / $pages", Modifier.padding(top = 12.dp), fontSize = 13.sp)
            TextButton({ page = current + 1 }, enabled = current + 1 < pages) { Text("Next") }
        }
        Text("A connected meter supplies watts to the overlay, all broadcasts, and ERG. Cadence and resistance still come from the bike.",
            fontSize = 12.sp, color = Color(0xFF9EAEC0))
    }
}

@Composable
internal fun SettingsDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 660.dp).fillMaxWidth(.9f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = Color(0xFF1B2430), contentColor = Color(0xFFE8EEF5)) {
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
