package com.spop.poverlay

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.releases.Release
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.ui.theme.ErrorColor
import com.spop.poverlay.ui.theme.LatoFontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite

@Composable
fun ConfigurationPage(viewModel: ConfigurationViewModel) {
    val showPermissionInfo by remember { viewModel.showPermissionInfo }
    val latestRelease by remember { viewModel.latestRelease }

    Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
    ) {
        if (showPermissionInfo) {
            PermissionPage(viewModel::onGrantPermissionClicked)
        } else {
            val timerShownWhenMinimized by
                    viewModel.showTimerWhenMinimized.collectAsStateWithLifecycle(
                            initialValue = true
                    )
            // calories-on-mini setting removed; calories visibility is now toggled from the main overlay
            val bleTxEnabled by
                    viewModel.bleTxEnabled.collectAsStateWithLifecycle(initialValue = false)
            val bleFtmsDeviceName by
                    viewModel.bleFtmsDeviceName.collectAsStateWithLifecycle(
                            initialValue = "Grupetto FTMS"
                    )
            val hrConnectedDevice by
                    viewModel.hrConnectedDevice.collectAsStateWithLifecycle(initialValue = null)
            val hrDiscoveredDevices by
                    viewModel.hrDiscoveredDevices.collectAsStateWithLifecycle(initialValue = emptyList())
            val hrSavedDevices by
                    viewModel.hrSavedDevices.collectAsStateWithLifecycle(initialValue = emptyList())
            val hrIsScanning by
                    viewModel.hrIsScanning.collectAsStateWithLifecycle(initialValue = false)
            StartServicePage(
                    timerShownWhenMinimized,
                    viewModel::onShowTimerWhenMinimizedClicked,
                    bleTxEnabled,
                    viewModel::onBleTxEnabledClicked,
                    bleFtmsDeviceName,
                    hrConnectedDevice,
                    hrDiscoveredDevices,
                    hrSavedDevices,
                    hrIsScanning,
                    viewModel::startHeartRateDiscovery,
                    viewModel::stopHeartRateDiscovery,
                    viewModel::connectHeartRateDevice,
                    viewModel::forgetHeartRateDevice,
                    viewModel::onStartServiceClicked,
                    viewModel::onRestartClicked,
                    viewModel::onClickedRelease,
                    latestRelease
            )
        }
    }
}

@Composable
private fun StartServicePage(
        timerShownWhenMinimized: Boolean,
        onTimerShownWhenMinimizedToggled: (Boolean) -> Unit,
        bleTxEnabled: Boolean,
        onBleTxEnabledToggled: (Boolean) -> Unit,
        bleFtmsDeviceName: String,
        hrConnectedDevice: HeartRateDevice?,
        hrDiscoveredDevices: List<HeartRateDevice>,
        hrSavedDevices: List<HeartRateDevice>,
        hrIsScanning: Boolean,
        onStartHeartRateDiscovery: () -> Unit,
        onStopHeartRateDiscovery: () -> Unit,
        onConnectHeartRateDevice: (HeartRateDevice) -> Unit,
        onForgetHeartRateDevice: (String) -> Unit,
        onClickedStartOverlay: () -> Unit,
        onClickedRestartApp: () -> Unit,
        onClickedRelease: (Release) -> Unit,
        latestRelease: Release?
) {
    var showHeartRateDialog by remember { mutableStateOf(false) }

    Text(
            text = "Grupetto: An overlay for your Peloton bike",
            fontSize = 50.sp,
            fontWeight = FontWeight.Bold
    )
    Text(
            text = "Note: Not endorsed with, associated with, or supported by Peloton",
            fontSize = 25.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = "Nov 2025 Update with colored metrics, charts, and max",
        fontSize = 18.sp,
        fontStyle = FontStyle.Italic,
        color = Color.Gray
    )
        Spacer(modifier = Modifier.height(32.dp))
    Button(
            onClick = onClickedStartOverlay,
    ) {
        Text(
                text = "Click here to start the overlay",
                fontSize = 30.sp,
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Show timer when the overlay minimized?", fontSize = 20.sp)
        Checkbox(
                checked = timerShownWhenMinimized,
                onCheckedChange = onTimerShownWhenMinimizedToggled
        )
    }
        // calories mini-view option removed; use main overlay toggles instead
    // BLE FTMS Settings
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
                text = "Enable BLE TX (Transmission)?",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
        )
        Checkbox(checked = bleTxEnabled, onCheckedChange = onBleTxEnabledToggled)
    }

    if (bleTxEnabled) {
        Row {
            Text(text = "BLE TX is enabled and running!", fontSize = 14.sp, color = Color.Green)

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                    text = " Look for '$bleFtmsDeviceName' in your fitness app's device list",
                    fontSize = 14.sp
            )
        }
    } else {
        Spacer(modifier = Modifier.height(8.dp))

        Text(
                text = "💡 Enable to broadcast bike data to apps like Zwift, TrainerRoad, etc.",
                fontSize = 14.sp,
                color = Color.Gray
        )
    }

        Spacer(modifier = Modifier.height(12.dp))

        val hrStatus = hrConnectedDevice?.let {
                "Connected to ${it.name ?: it.address}"
        } ?: "Disconnected"

            Button(
                    onClick = { showHeartRateDialog = true },
                    modifier = Modifier
                            .wrapContentWidth()
                            .padding(horizontal = 6.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF2F2F2F))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = "Heart Rate",
                                        tint = Color.Red
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                                Text(
                                                text = "Manage Heart Rate Monitors",
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                )
                                Text(
                                                text = hrStatus,
                                                fontSize = 13.sp,
                                                color = Color.Gray,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                )
                        }
                }
        }

        if (showHeartRateDialog) {
                HeartRateManagerDialog(
                                connectedDevice = hrConnectedDevice,
                                discoveredDevices = hrDiscoveredDevices,
                                savedDevices = hrSavedDevices,
                                isScanning = hrIsScanning,
                                onStartDiscovery = onStartHeartRateDiscovery,
                                onStopDiscovery = onStopHeartRateDiscovery,
                                onConnectDevice = onConnectHeartRateDevice,
                                onForgetDevice = onForgetHeartRateDevice,
                                onDismiss = { showHeartRateDialog = false }
                )
        }

    Spacer(modifier = Modifier.height(8.dp))

    if (latestRelease == null) {
        Text(text = "Couldn't check for updates")
    } else {
        val formattedDate = DateUtils.getRelativeTimeSpanString(latestRelease.createdAt.time)
        val releaseText =
                if (latestRelease.isCurrentlyInstalled) {
                    buildAnnotatedString {
                        "Grupetto is up to date: ${latestRelease.tagName} • $formattedDate • ${latestRelease.friendlyName}"
                    }
                } else {
                    buildAnnotatedString {
                        append("⭐ ")
                        withStyle(style = SpanStyle(textDecoration = TextDecoration.Underline)) {
                            append(
                                    "New version released $formattedDate: ${latestRelease.friendlyName}."
                            )
                        }
                    }
                }
        ClickableText(
                text = releaseText,
                style =
                        LocalTextStyle.current.copy(
                                fontSize = 20.sp,
                                color = LocalContentColor.current
                        )
        ) { onClickedRelease(latestRelease) }
    }

    Spacer(modifier = Modifier.height(40.dp))
    Button(
            onClick = onClickedRestartApp,
            colors = ButtonDefaults.buttonColors(backgroundColor = ErrorColor),
    ) {
        Text(
                text = "Restart Grupetto",
                fontSize = 20.sp,
                fontStyle = FontStyle.Italic,
                color = Color.White
        )
    }
    Spacer(modifier = Modifier.height(10.dp))

    Text(
            "Device: ${Build.DEVICE}\t" +
                    "SDK: ${Build.VERSION.RELEASE}\t" +
                    "OS Version: ${Build.FINGERPRINT}\t",
            color = LocalContentColor.current.copy(alpha = .5f)
    )
}

@Composable
private fun HeartRateManagerDialog(
                connectedDevice: HeartRateDevice?,
                discoveredDevices: List<HeartRateDevice>,
                savedDevices: List<HeartRateDevice>,
                isScanning: Boolean,
                onStartDiscovery: () -> Unit,
                onStopDiscovery: () -> Unit,
                onConnectDevice: (HeartRateDevice) -> Unit,
                onForgetDevice: (String) -> Unit,
                onDismiss: () -> Unit
) {
        val zone12 = remember { mutableStateOf("") }
        val zone23 = remember { mutableStateOf("") }
        val zone34 = remember { mutableStateOf("") }
        val zone45 = remember { mutableStateOf("") }
        val savedZone12 by HeartRateManager.zone12.collectAsStateWithLifecycle(initialValue = null)
        val savedZone23 by HeartRateManager.zone23.collectAsStateWithLifecycle(initialValue = null)
        val savedZone34 by HeartRateManager.zone34.collectAsStateWithLifecycle(initialValue = null)
        val savedZone45 by HeartRateManager.zone45.collectAsStateWithLifecycle(initialValue = null)
        androidx.compose.runtime.LaunchedEffect(savedZone12, savedZone23, savedZone34, savedZone45) {
                if (zone12.value.isBlank() && savedZone12 != null) zone12.value = savedZone12.toString()
                if (zone23.value.isBlank() && savedZone23 != null) zone23.value = savedZone23.toString()
                if (zone34.value.isBlank() && savedZone34 != null) zone34.value = savedZone34.toString()
                if (zone45.value.isBlank() && savedZone45 != null) zone45.value = savedZone45.toString()
        }
        fun parseZone(value: String): Int? = value.toIntOrNull()
        fun updateZones() {
                HeartRateManager.setHeartRateZones(
                        parseZone(zone12.value),
                        parseZone(zone23.value),
                        parseZone(zone34.value),
                        parseZone(zone45.value)
                )
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
                onStartDiscovery()
                HeartRateManager.setManaging(true)
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose {
                        onStopDiscovery()
                        HeartRateManager.setManaging(false)
                }
        }

        AlertDialog(
                        onDismissRequest = onDismiss,
                        title = { Text("Manage Heart Rate Monitors") },
                        text = {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                        SectionHeader("Connected")
                                        if (connectedDevice == null) {
                                                Text("None", color = Color.Gray)
                                        } else {
                                                HeartRateDeviceRow(
                                                                device = connectedDevice,
                                                                actionLabel = "Forget",
                                                                onAction = { onForgetDevice(connectedDevice.address) }
                                                )
                                        }

                                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                                        SectionHeader("Discovered")
                                        if (discoveredDevices.isEmpty()) {
                                                Text(
                                                                text = if (isScanning) "Scanning..." else "None",
                                                                color = Color.Gray
                                                )
                                        } else {
                                                discoveredDevices.forEach { device ->
                                                        HeartRateDeviceRow(
                                                                        device = device,
                                                                        actionLabel = "Connect",
                                                                        onAction = { onConnectDevice(device) }
                                                        )
                                                }
                                        }

                                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                                        SectionHeader("Saved")
                                        val filteredSaved = savedDevices.filter { it.address != connectedDevice?.address }
                                        if (filteredSaved.isEmpty()) {
                                                Text("None", color = Color.Gray)
                                        } else {
                                                filteredSaved.forEach { device ->
                                                        HeartRateDeviceRow(
                                                                        device = device,
                                                                        actionLabel = "Forget",
                                                                        onAction = { onForgetDevice(device.address) }
                                                        )
                                                }
                                        }

                                        Divider(modifier = Modifier.padding(vertical = 8.dp))

                                        SectionHeader("Heart Rate Zone Transitions")
                                        Row(
                                                modifier = Modifier.wrapContentWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("Zone 1–2", fontSize = 12.sp, color = Color.Gray)
                                                        TextField(
                                                                value = zone12.value,
                                                                onValueChange = {
                                                                        zone12.value = it.filter { ch -> ch.isDigit() }
                                                                        updateZones()
                                                                },
                                                                modifier = Modifier.width(72.dp),
                                                                placeholder = { Text("bpm") },
                                                                singleLine = true
                                                        )
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("Zone 2–3", fontSize = 12.sp, color = Color.Gray)
                                                        TextField(
                                                                value = zone23.value,
                                                                onValueChange = {
                                                                        zone23.value = it.filter { ch -> ch.isDigit() }
                                                                        updateZones()
                                                                },
                                                                modifier = Modifier.width(72.dp),
                                                                placeholder = { Text("bpm") },
                                                                singleLine = true
                                                        )
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("Zone 3–4", fontSize = 12.sp, color = Color.Gray)
                                                        TextField(
                                                                value = zone34.value,
                                                                onValueChange = {
                                                                        zone34.value = it.filter { ch -> ch.isDigit() }
                                                                        updateZones()
                                                                },
                                                                modifier = Modifier.width(72.dp),
                                                                placeholder = { Text("bpm") },
                                                                singleLine = true
                                                        )
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                        Text("Zone 4–5", fontSize = 12.sp, color = Color.Gray)
                                                        TextField(
                                                                value = zone45.value,
                                                                onValueChange = {
                                                                        zone45.value = it.filter { ch -> ch.isDigit() }
                                                                        updateZones()
                                                                },
                                                                modifier = Modifier.width(72.dp),
                                                                placeholder = { Text("bpm") },
                                                                singleLine = true
                                                        )
                                                }
                                        }
                                }
                        },
                        confirmButton = {
                                TextButton(onClick = onDismiss) { Text("Close") }
                        }
        )
}

@Composable
private fun SectionHeader(title: String) {
        Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
}

@Composable
private fun HeartRateDeviceRow(
                device: HeartRateDevice,
                actionLabel: String,
                onAction: () -> Unit
) {
        Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
        ) {
                Column(modifier = Modifier.weight(1f)) {
                        Text(text = device.name ?: "Unknown", fontSize = 14.sp)
                        Text(
                                        text = device.address,
                                        fontSize = 12.sp,
                                        color = Color.Gray
                        )
                }
                TextButton(onClick = onAction) {
                        Text(actionLabel)
                }
        }
}

@Composable
private fun PermissionPage(onClickedGrantPermission: () -> Unit) {
    Text(
            text = "Grupetto Needs Permission To Draw Over Other Apps",
            fontSize = 40.sp,
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold
    )
    Text(
            text = "It uses this permission to draw an overlay with your bike's sensor data",
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal
    )
    Spacer(modifier = Modifier.height(10.dp))
    Button(onClick = onClickedGrantPermission) { Text(text = "Grant Permission") }
}
