package com.spop.poverlay

import com.spop.poverlay.ui.components.GrupettoTabs
import com.spop.poverlay.ui.components.SavedSensorList
import com.spop.poverlay.ui.components.SensorPickerScanEffect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
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
import com.spop.poverlay.sensor.power.PowerDisplay
import com.spop.poverlay.sensor.power.DisplayPowerSource
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
    val overlayItems by viewModel.overlayItems.collectAsStateWithLifecycle()
    val ble by viewModel.bleTxEnabled.collectAsStateWithLifecycle()
    val dircon by viewModel.dirConEnabled.collectAsStateWithLifecycle()
    val dirconStatus by viewModel.dirConStatus.collectAsStateWithLifecycle()
    val appConnected by viewModel.appConnected.collectAsStateWithLifecycle(initialValue = false)
    val ant by viewModel.antPlusTxEnabled.collectAsStateWithLifecycle()
    val running by viewModel.isOverlayRunning.collectAsStateWithLifecycle()
    val background by viewModel.backgroundLocationGranted.collectAsStateWithLifecycle()
    val hr by viewModel.hrConnectedDevice.collectAsStateWithLifecycle()
    val discovered by viewModel.hrDiscoveredDevices.collectAsStateWithLifecycle()
    val saved by viewModel.hrSavedDevices.collectAsStateWithLifecycle()
    val scanning by viewModel.hrIsScanning.collectAsStateWithLifecycle()
    val match by viewModel.hrMatchByName.collectAsStateWithLifecycle()
    val bike by viewModel.bikeControlState.collectAsStateWithLifecycle()
    val showShifters by viewModel.showShifters.collectAsStateWithLifecycle()
    val shifterInset by viewModel.shifterInset.collectAsStateWithLifecycle()
    val displayedPower by viewModel.livePowerDisplay.collectAsStateWithLifecycle(initialValue = PowerDisplay(Float.NaN))
    val watts = displayedPower.watts
    val cadence by viewModel.liveCadence.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val resistance by viewModel.liveResistance.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val speed by viewModel.liveSpeed.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val incline by viewModel.liveIncline.collectAsStateWithLifecycle(initialValue = Float.NaN)
    val bpm by HeartRateManager.heartRate.collectAsStateWithLifecycle()
    val heartStatus by HeartRateManager.status.collectAsStateWithLifecycle()
    val powerStatus by viewModel.powerMeterStatus.collectAsStateWithLifecycle()
    val meter by viewModel.powerMeterDevice.collectAsStateWithLifecycle()
    val externalWatts by viewModel.powerMeterReading.collectAsStateWithLifecycle()
    val grupettoTable by viewModel.grupettoCalibrationState.collectAsStateWithLifecycle()
    var dialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    // Basic settings by default; Advanced holds the rest, one section per page.
    var advanced by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("trainer") }
    androidx.compose.runtime.LaunchedEffect(dialog) {
        if (dialog == "grupetto-calibration") viewModel.showCalibrationTable()
    }
    androidx.compose.runtime.LaunchedEffect(viewModel.showCalibration.value) {
        if (viewModel.showCalibration.value) { viewModel.showCalibrationTable(); dialog = "grupetto-calibration"; viewModel.showCalibration.value = false }
    }
    androidx.compose.runtime.LaunchedEffect(viewModel.showGuidedCalibration.value) {
        if (viewModel.showGuidedCalibration.value) { dialog = "calibration"; viewModel.showGuidedCalibration.value = false }
    }
    var previewShifters by remember { mutableStateOf(false) }
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
                    // Start the overlay and stay here (more settings to change); Ride hides this screen.
                    if (!running) {
                        Button(viewModel::startOverlay, Modifier.heightIn(min = 48.dp)) { Text("Start overlay") }
                        // Out of settings without quitting anything.
                        OutlinedButton(viewModel::onRideClicked, Modifier.heightIn(min = 48.dp).padding(start = 12.dp)) { Text("Close") }
                    } else {
                        if (advanced) OutlinedButton(viewModel::onStartServiceClicked, Modifier.heightIn(min = 48.dp).padding(end = 12.dp)) {
                            Text("Restart overlay")
                        }
                        Button(viewModel::onRideClicked, Modifier.heightIn(min = 48.dp)) { Text("Ride (hide settings)") }
                    }
                    if (advanced) TextButton({ advanced = false }, Modifier.padding(start = 12.dp)) { Text("Basic settings") }
                    // Quitting drops the overlay, ERG and every app's connection: name it fully and ask first.
                    TextButton({ dialog = "quit" }, Modifier.padding(start = 12.dp)) { Text("Quit Grupetto") }
                }
                Row(Modifier.fillMaxWidth().height(58.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.isTread) {
                        LiveMetric("SPEED", displayMetric(speed, 1), "mph", Color(0xFF60A5FA), Modifier.weight(1f))
                        LiveMetric("INCLINE", displayMetric(incline, 1), "%", Color(0xFF34D399), Modifier.weight(1f))
                    } else {
                        LiveMetric(if (displayedPower.source == DisplayPowerSource.Corrected) "CORRECTED" else "POWER",
                            displayMetric(watts), "W", Color(0xFFFBBF24), Modifier.weight(1f))
                        LiveMetric("CADENCE", displayMetric(cadence), "rpm", Color(0xFF34D399), Modifier.weight(1f))
                        LiveMetric("RESISTANCE", displayMetric(resistance), "%", Color(0xFF60A5FA), Modifier.weight(1f))
                    }
                    LiveMetric("HEART RATE", bpm?.toString() ?: "—", "bpm", Color(0xFFFB7185), Modifier.weight(1f))
                }
                if (advanced) {
                    // One page per section, chosen on the left; everything Basic already has stays on Basic.
                    val sections = listOfNotNull(
                        ("trainer" to "Trainer tuning").takeIf { bike.connected },
                        "power" to "Power & calibration",
                        "heart" to "Heart rate",
                        "connections" to "Connections",
                        ("shifters" to "Shift buttons").takeIf { bike.connected },
                        "diagnostics" to "Diagnostics",
                        "about" to "About & updates",
                    )
                    val current = sections.firstOrNull { it.first == section } ?: sections.first()
                    Row(Modifier.weight(2f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            sections.forEach { (key, label) ->
                                val selected = key == current.first
                                TextButton({ section = key }, Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        backgroundColor = if (selected) Color(0xFF223142) else Color.Transparent,
                                        contentColor = if (selected) Color(0xFFE6EDF5) else Color(0xFFB4C2D1))) {
                                    Text(label, Modifier.fillMaxWidth(), fontSize = 15.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                                }
                            }
                        }
                        SettingsTile(current.second, Modifier.weight(1f).fillMaxHeight(), Color(0xFFFBBF24)) {
                            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                when (current.first) {
                                    "trainer" -> {
                                        Text("A connected training app sets the mode by itself. Pick one here only to ride without an app; the sliders change how it feels.",
                                            fontSize = 13.sp, color = Color(0xFF9EAEC0))
                                        TrainerControls(bike, viewModel::startErg, viewModel::startSimulation,
                                            viewModel::stopBikeControl, viewModel::setBikeResistance, viewModel::setBikeTuning)
                                    }
                                    "power" -> {
                                        CalibrationSensorSummary(
                                            grupettoTable.active, grupettoTable.recording, meter?.let { it.name ?: it.address },
                                            externalWatts?.let { "${it.watts.coerceAtLeast(0)} W" } ?: "—", powerStatus)
                                        Text(when (displayedPower.source) {
                                            DisplayPowerSource.Corrected -> "Displayed power: Grupetto calibration"
                                            DisplayPowerSource.Pedals -> "Displayed power: external meter"
                                            DisplayPowerSource.Bike -> "Displayed power: built-in sensors"
                                        }, fontSize = 13.sp, color = Color(0xFF9EAEC0))
                                        TileActionButton("Pair & manage power meters", { dialog = "power" })
                                        if (viewModel.calibrationSupported) {
                                            TileActionButton("Calibrate using power pedals", { viewModel.showCalibrationTable(); dialog = "grupetto-calibration" })
                                        }
                                        Text("Broadcast power matches the large overlay number. Pedals supply ERG feedback. Changing or losing the ERG power source stops ERG until restarted.",
                                            fontSize = 13.sp, color = Color(0xFF9EAEC0))
                                    }
                                    "heart" -> {
                                        SensorSummary("Heart rate", hr?.let { it.name ?: it.address } ?: "Not connected",
                                            bpm?.let { "$it bpm" } ?: "—", Color(0xFFFB7185), heartStatus)
                                        TileActionButton("Heart-rate monitors & zones", { dialog = "heart" })
                                    }
                                    "connections" -> {
                                        SettingSwitch("Bluetooth FTMS", ble, viewModel::onBleTxEnabledClicked)
                                        ConnectionSummary("Bluetooth", if (viewModel.isPreview) "Preview" else if (ble) "On · Grupetto" else "Off", ble)
                                        SettingSwitch("Network / DirCon", dircon, viewModel::onDirConEnabledClicked)
                                        ConnectionSummary("Network", when {
                                            viewModel.isPreview -> "Preview"
                                            !dircon -> "Off"
                                            dirconStatus == com.spop.poverlay.dircon.DirConStatus.Listening -> "On · DirCon"
                                            dirconStatus == com.spop.poverlay.dircon.DirConStatus.Starting -> "Starting…"
                                            dirconStatus == com.spop.poverlay.dircon.DirConStatus.Failed -> "Unavailable · check connection"
                                            else -> "Not running"
                                        }, dircon && dirconStatus == com.spop.poverlay.dircon.DirConStatus.Listening)
                                        Text("Bluetooth name: Grupetto", fontSize = 14.sp, color = Color(0xFF9EAEC0))
                                        if (boot && !background) Text("Allow location all the time in Android settings for sensor reconnection after boot.",
                                            fontSize = 12.sp, color = Color(0xFFFBBF24))
                                        if (viewModel.antPlusSupported) {
                                            Divider(Modifier.padding(vertical = 8.dp))
                                            SettingSwitch("ANT+ broadcast", ant, viewModel::onAntPlusTxEnabledClicked)
                                            Text("ANT+ sensor IDs: power 1 · speed/cadence 2 · HR 3", fontSize = 14.sp)
                                        }
                                    }
                                    "shifters" -> ShifterSettings(showShifters, shifterInset, viewModel::setShowShifters, viewModel::setShifterInset,
                                        onPreview = { previewShifters = it })
                                    "diagnostics" -> {
                                        Text((if (viewModel.isTread) "Tread" else "Bike") + "   ·   Model ${Build.MODEL}   ·   Android ${Build.VERSION.RELEASE}",
                                            fontSize = 13.sp, color = Color(0xFF9EAEC0))
                                        val discovery by viewModel.discoveryState.collectAsStateWithLifecycle()
                                        val connection by viewModel.discoveryConnection.collectAsStateWithLifecycle()
                                        com.spop.poverlay.sensor.discovery.BikeDiscoveryPanel(discovery, connection, viewModel.isPreview,
                                            viewModel::discoverBikeServices, viewModel::cancelBikeDiscovery, viewModel::useDiscoveredBikeService,
                                            viewModel::restoreAutomaticBikeService, viewModel::requestBikeDiscoveryReport)
                                    }
                                    else -> {
                                        Text("Version ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE}")
                                        Text("Device: ${Build.MODEL}", fontSize = 14.sp)
                                        val latest = release
                                        Text(latest?.let { if (it.isCurrentlyInstalled) "You're up to date" else "Available: ${it.friendlyName}" }
                                            ?: "Couldn't check for updates")
                                        if (latest != null) TextButton({ viewModel.onClickedRelease(latest) }) { Text("View release") }
                                        Text("Bike+ ERG work based on dwj300's contribution (PR #50).", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    BasicSettings(Modifier.weight(2f), viewModel.isTread, bike, appConnected, running, ble, dircon, timer, boot, showShifters,
                        overlayItems, OverlayItem.forDevice(viewModel.isTread, viewModel.calibrationSupported),
                        hr?.let { it.name ?: it.address }, bpm, meter?.let { it.name ?: it.address }, externalWatts?.watts,
                        watts.isFinite() || cadence.isFinite(),
                        onOverlayItem = viewModel::setOverlayItem, onTimer = viewModel::onShowTimerWhenMinimizedClicked,
                        onBoot = viewModel::onAutoStartOnBootClicked, onBle = viewModel::onBleTxEnabledClicked,
                        onDirCon = viewModel::onDirConEnabledClicked, onShifters = viewModel::setShowShifters,
                        onDialog = { dialog = it }, onAdvanced = { advanced = true }, onStartOverlay = viewModel::startOverlay)
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
                    CoffeeLink(Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    when (dialog) {
        "power" -> PowerMeterDialog(viewModel) { dialog = null }
        "grupetto-calibration" -> SettingsDialog("Calibrate using power pedals", { dialog = null }, compact = true) {
            val calibration by viewModel.calibrationState.collectAsStateWithLifecycle()
            val captureSettings by viewModel.calibrationCaptureSettings.collectAsStateWithLifecycle()
            val flywheelTemperature by viewModel.flywheelTemperature.collectAsStateWithLifecycle()
            val calibrationMeterReady by viewModel.calibrationMeterReady.collectAsStateWithLifecycle(initialValue = false)
            val livePosition by viewModel.liveCalibrationPosition.collectAsStateWithLifecycle(initialValue = null)
            Column {
                com.spop.poverlay.calibration.GrupettoCalibrationPanel(grupettoTable,
                    viewModel::recordGrupettoCalibration, viewModel::activateGrupettoCalibration, viewModel::clearGrupettoCalibration,
                    viewModel::startGuidedCalibration,
                    meterReady = calibrationMeterReady, capture = calibration,
                    metrics = com.spop.poverlay.calibration.CalibrationLiveMetrics(
                        displayedPower.nativeWatts.toDouble(), externalWatts?.watts?.toDouble(), cadence.toDouble(), resistance.toDouble(),
                        displayedPower.watts.toDouble().takeIf { displayedPower.source == DisplayPowerSource.Corrected }),
                    fileControls = {
                        com.spop.poverlay.calibration.CalibrationFileControls(
                            hasData = grupettoTable.count > 0 || grupettoTable.savedCount > 0,
                            canLoad = !grupettoTable.recording && !calibration.enabled,
                            export = viewModel::exportCalibrationBackup, load = viewModel::loadCalibrationBackup)
                    }, onManualEdit = { dialog = "manual-calibration" }, livePosition = livePosition,
                    captureSettings = captureSettings, onCaptureSettings = viewModel::setCalibrationCaptureSettings,
                    temperature = flywheelTemperature, onRoomTemperature = viewModel::setRoomTemperature,
                    onTemperatureUnit = viewModel::setTemperatureUnit,
                    onRefit = { dialog = "refit-calibration" })
            }
        }
        "refit-calibration" -> com.spop.poverlay.calibration.CalibrationRefitDialog(
            recording = grupettoTable.recording || viewModel.calibrationState.collectAsStateWithLifecycle().value.enabled,
            onPreview = viewModel::previewCalibrationRefit,
            onApply = viewModel::applyCalibrationRefit,
            onUndo = viewModel::undoCalibrationRefit,
            onClose = { dialog = "grupetto-calibration" })
        "manual-calibration" -> {
            val session = viewModel.manualCalibrationSession ?: (grupettoTable.displayTable ?: grupettoTable.table)?.let {
                com.spop.poverlay.calibration.CalibrationEditHistory(it).also { session -> viewModel.manualCalibrationSession = session }
            }
            if (session != null) com.spop.poverlay.calibration.CalibrationManualEditor(session.initial, grupettoTable.active,
                viewModel::saveManualCalibration, {
                    viewModel.manualCalibrationSession = null
                    dialog = "grupetto-calibration"
                }, session)
            else SettingsDialog("Manual editor", { dialog = "grupetto-calibration" }) {
                Text("Record at least one usable calibration effort to create an editable table.")
            }
        }
        "calibration" -> SettingsDialog("Calibrate using power pedals", { dialog = null }, compact = true) {
            val calibration by viewModel.calibrationState.collectAsStateWithLifecycle()
            val calibrationMeterReady by viewModel.calibrationMeterReady.collectAsStateWithLifecycle(initialValue = false)
            com.spop.poverlay.calibration.CalibrationPanel(calibration,
                { if (it) viewModel.startGuidedCalibration() else viewModel.setCalibrationEnabled(false) }, viewModel::clearCalibrationCapture,
                viewModel::restCalibration, viewModel::easierCalibration,
                viewModel::skipCalibrationTarget, viewModel::selectCalibrationTarget,
                viewModel::harderCalibration, { viewModel.showCalibrationTable(); dialog = "grupetto-calibration" },
                localCalibration = grupettoTable, onLocalReview = { dialog = "grupetto-calibration" }, meterReady = calibrationMeterReady,
                liveMetrics = com.spop.poverlay.calibration.CalibrationLiveMetrics(
                    displayedPower.nativeWatts.toDouble(), externalWatts?.watts?.toDouble(), cadence.toDouble(), resistance.toDouble(),
                    displayedPower.watts.toDouble().takeIf { displayedPower.source == DisplayPowerSource.Corrected }))
        }
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
        "quit" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Quit Grupetto?") },
            text = {
                Text("The overlay closes, ERG stops, and " +
                    (bike.externalControl?.let { "the app connected over $it loses the bike." } ?: "training apps lose the bike."))
            },
            confirmButton = { TextButton({ dialog = null; viewModel.onQuitClicked() }) { Text("Quit") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } }
        )
        "shifters" -> SettingsDialog("Shift buttons", { dialog = null }) {
            ShifterSettings(showShifters, shifterInset, viewModel::setShowShifters, viewModel::setShifterInset,
                onPreview = { previewShifters = it })
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
    ShifterPositionPreview(bike.connected && previewShifters &&
        (dialog == "shifters" || (advanced && section == "shifters" && dialog == null)), shifterInset)
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
internal fun CalibrationSensorSummary(active: Boolean, recording: Boolean, meterName: String?,
    value: String, status: String? = null) {
    SensorSummary("Power meter",
        com.spop.poverlay.sensor.power.calibrationSensorLabel(active, recording, meterName),
        value, Color(0xFFFBBF24), status,
        nameColor = if (active || (recording && meterName != null)) Color(0xFF6EE7B7) else Color(0xFF8796A8))
}

@Composable
private fun SensorSummary(label: String, name: String, value: String, accent: Color, status: String? = null,
    nameColor: Color = Color(0xFF8796A8)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, lineHeight = 16.sp, color = Color(0xFFBAC6D3))
            Text(name, fontSize = 12.sp, lineHeight = 15.sp, color = nameColor, maxLines = 3, overflow = TextOverflow.Ellipsis)
            status?.let { Text(it, fontSize = 11.sp, lineHeight = 14.sp, color = accent, maxLines = 2, overflow = TextOverflow.Ellipsis) }
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
    val connecting by viewModel.powerMeterConnecting.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(0) }
    SensorPickerScanEffect(true, viewModel::managePowerMeters)
    SettingsDialog("External Sensors · Power", onDismiss, scrollable = true) {
        SensorSummary("Power meter",
            connected?.let { it.name ?: it.address } ?: status,
            "${reading?.watts?.coerceAtLeast(0) ?: "—"} W", Color(0xFFFBBF24))
        SavedSensorList(saved, connected?.address, connecting?.address,
            viewModel::connectPowerMeter, viewModel::disconnectPowerMeter, viewModel::forgetPowerMeter)
        Text(if (viewModel.isPreview) "Model emulation: pairing is disabled" else status,
            fontSize = 12.sp, color = Color(0xFF9EAEC0))
        Text("Discover power meters", fontWeight = FontWeight.SemiBold)
        val devices = discovered.filter { candidate -> saved.none { it.address == candidate.address } && candidate.address != connected?.address }
        val pages = maxOf(1, (devices.size + 1) / 2)
        val current = page.coerceAtMost(pages - 1)
        Column(Modifier.height(128.dp).padding(top = 8.dp)) {
            if (devices.isEmpty()) Text(if (scanning) "Scanning… Wake your power meter by pedaling." else if (connected != null) "Disconnect the current meter to discover another." else "No new power meters found")
            devices.drop(current * 2).take(2).forEach { device ->
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(device.name ?: "Power meter", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(device.address, fontSize = 12.sp, color = Color(0xFF9EAEC0))
                    }
                    TextButton({ viewModel.connectPowerMeter(device) }, enabled = connected == null && connecting == null) { Text("Connect") }
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
internal fun SettingsDialog(title: String, onDismiss: () -> Unit, compact: Boolean = false, scrollable: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = if (compact) 1000.dp else 760.dp).fillMaxWidth(.94f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = Color(0xFF1B2430), contentColor = Color(0xFFE8EEF5)) {
            Column(Modifier.padding(if (compact) 16.dp else 24.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), fontSize = if (compact) 20.sp else 22.sp, fontWeight = FontWeight.Bold)
                    TextButton(onDismiss) { Text("Done") }
                }
                Spacer(Modifier.height(8.dp))
                if (scrollable) Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) { content() }
                else content()
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
    val connecting by HeartRateManager.connectingDevice.collectAsStateWithLifecycle()
    SensorPickerScanEffect(tab == 0, HeartRateManager::setManaging)
    SettingsDialog("Heart rate", onDismiss, scrollable = true) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(connectedDevice?.name ?: connectedDevice?.address ?: "No monitor connected",
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${bpm ?: "—"} bpm", color = Color(0xFFFB7185))
            }
        }
        Spacer(Modifier.height(8.dp))
        SavedSensorList(savedDevices, connectedDevice?.address, connecting?.address,
            onConnectDevice, onDisconnectConnectedDevice, onForgetDevice)
        Spacer(Modifier.height(8.dp))
        GrupettoTabs(tab, listOf("Discover", "Zones"), { tab = it; page = 0 })
        Spacer(Modifier.height(12.dp))
        if (tab == 1) {
            HeartRateZoneSettings(listOf(zone12, zone23, zone34, zone45)) { values ->
                HeartRateManager.setHeartRateZones(values[0], values[1], values[2], values[3])
            }
        } else {
            val devices = discoveredDevices.filter { candidate ->
                candidate.address != connectedDevice?.address && savedDevices.none { it.address == candidate.address }
            }
            val pages = maxOf(1, (devices.size + 2) / 3)
            val current = page.coerceAtMost(pages - 1)
            Column(Modifier.height(180.dp)) {
                if (devices.isEmpty()) Text(if (isScanning) "Scanning for monitors…" else if (connectedDevice != null) "Disconnect the current monitor to discover another." else "No new monitors found")
                devices.drop(current * 3).take(3).forEach { device ->
                    Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name ?: "Unknown monitor", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(device.address, fontSize = 12.sp, color = Color(0xFF9EAEC0))
                        }
                        TextButton({ onConnectDevice(device) }, enabled = connectedDevice == null && connecting == null) { Text("Connect") }
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

/**
 * The everyday settings: is everything working, what the overlay shows, how apps connect, and the
 * sensors. Everything else (tuning, calibration, ANT+, zones, diagnostics) is one tap away in Advanced.
 */
@Composable
private fun BasicSettings(
    modifier: Modifier, isTread: Boolean, bike: com.spop.poverlay.control.ControlState, appConnected: Boolean, overlayRunning: Boolean,
    ble: Boolean, dircon: Boolean, timer: Boolean, boot: Boolean, shifters: Boolean,
    overlayItems: Set<OverlayItem>, itemsForDevice: List<OverlayItem>,
    heartRateMonitor: String?, bpm: Int?, powerMeter: String?, meterWatts: Int?, bikeReporting: Boolean,
    onOverlayItem: (OverlayItem, Boolean) -> Unit, onTimer: (Boolean) -> Unit, onBoot: (Boolean) -> Unit,
    onBle: (Boolean) -> Unit, onDirCon: (Boolean) -> Unit, onShifters: (Boolean) -> Unit,
    onDialog: (String) -> Unit, onAdvanced: () -> Unit, onStartOverlay: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val machine = if (isTread) "Tread" else "Bike"
            StatusCheck(Modifier.weight(1f), bikeReporting,
                if (bikeReporting) "$machine found" else "Looking for the ${machine.lowercase()}",
                when {
                    !bikeReporting -> "Pedal or walk a few seconds"
                    bike.connected -> "ERG ready"
                    isTread -> "Reading speed and incline"
                    else -> "Reading power and cadence"
                })
            // Off: the check itself starts it (people tap the card, not the button in the corner).
            StatusCheck(Modifier.weight(1f), overlayRunning,
                if (overlayRunning) "Overlay running" else "Overlay off",
                if (overlayRunning) "Floating over the Peloton screen" else "Tap here to start it",
                onClick = if (overlayRunning) null else onStartOverlay)
            // Connected = an app is linked over Bluetooth or DirCon; in control = it has sent ERG or resistance.
            val app = bike.externalControl
            val connected = appConnected || app != null
            StatusCheck(Modifier.weight(1f), connected,
                if (connected) "App connected" else "No app connected",
                when {
                    app != null -> "Over $app, in control"
                    connected -> "Receiving the ${if (isTread) "Tread" else "bike"}'s numbers"
                    else -> "In your app, pick the trainer called Grupetto"
                })
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingsTile("Show on the overlay", Modifier.weight(1f).fillMaxHeight(), Color(0xFF34D399)) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tap to show or hide.", fontSize = 13.sp, color = Color(0xFF9EAEC0))
                    val toggles: List<Triple<String, Boolean, (Boolean) -> Unit>> =
                        itemsForDevice.map { item -> Triple(item.label, item in overlayItems, { on: Boolean -> onOverlayItem(item, on) }) } +
                            Triple("Timer when minimized", timer, onTimer)
                    toggles.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { (label, on, change) -> OverlayToggle(label, on, Modifier.weight(1f)) { change(!on) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    SettingSwitch("Start with the tablet", boot, onBoot)
                }
            }
            SettingsTile("Training apps", Modifier.weight(1f).fillMaxHeight(), Color(0xFF60A5FA)) {
                SettingSwitch("Bluetooth", ble, onBle)
                SettingSwitch("Network (DirCon)", dircon, onDirCon)
                Text("Leave both on. In Zwift, Gregario or any other app, pick the trainer called Grupetto: the app sets ERG or resistance.",
                    fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF9EAEC0))
                if (bike.connected) {
                    Spacer(Modifier.weight(1f))
                    SettingSwitch("Shift buttons on screen", shifters, onShifters)
                    if (shifters) TileActionButton("Shift button position", { onDialog("shifters") })
                }
            }
            SettingsTile("Sensors", Modifier.weight(1f).fillMaxHeight(), Color(0xFFFB7185)) {
                SensorSummary("Heart rate", heartRateMonitor ?: "Not paired", bpm?.let { "$it bpm" } ?: "—", Color(0xFFFB7185))
                TileActionButton(if (heartRateMonitor == null) "Pair a heart-rate monitor" else "Heart-rate monitors", { onDialog("heart") })
                Spacer(Modifier.height(4.dp))
                SensorSummary("Power meter (optional)", powerMeter ?: "None · using the bike's power",
                    meterWatts?.let { "${it.coerceAtLeast(0)} W" } ?: "—", Color(0xFFFBBF24))
                TileActionButton(if (powerMeter == null) "Pair a power meter" else "Power meters", { onDialog("power") })
                Spacer(Modifier.weight(1f))
                TileActionButton("Advanced settings", onAdvanced)
            }
        }
    }
}

@Composable
private fun StatusCheck(modifier: Modifier, ok: Boolean, title: String, detail: String, onClick: (() -> Unit)? = null) {
    val accent = if (ok) Color(0xFF34D399) else Color(0xFFFBBF24)
    Surface(if (onClick != null) modifier.clickable(onClick = onClick) else modifier, color = if (ok) Color(0xFF12301F) else Color(0xFF2A2412),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = .4f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.Info,
                contentDescription = null, tint = accent)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, fontSize = 13.sp, lineHeight = 17.sp, color = Color(0xFFB4C2D1), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One tap-to-toggle tile: shown (green, ticked) or hidden. */
@Composable
private fun OverlayToggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.heightIn(min = 48.dp).clickable(onClick = onClick),
        color = if (on) Color(0xFF12301F) else Color(0xFF111A23),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Color(0xFF2F7A55) else Color(0xFF2A3A4B))) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (on) Icons.Filled.CheckCircle else Icons.Filled.AddCircle,
                contentDescription = if (on) "Shown" else "Hidden", tint = if (on) Color(0xFF34D399) else Color(0xFF52728F))
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 15.sp, color = if (on) Color(0xFFE6EDF5) else Color(0xFF9EAEC0))
        }
    }
}
