package com.spop.poverlay

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spop.poverlay.releases.Release
import com.spop.poverlay.sensor.heartrate.HeartRateConnectionState
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import com.spop.poverlay.ui.theme.ErrorColor
import com.spop.poverlay.ui.theme.LatoFontFamily

@Composable
fun ConfigurationPage(viewModel: ConfigurationViewModel) {
    val showPermissionInfo by remember { viewModel.showPermissionInfo }
    val latestRelease by remember { viewModel.latestRelease }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showPermissionInfo) {
            PermissionPage(viewModel::onGrantPermissionClicked)
        } else {
            val timerShownWhenMinimized by
                viewModel.showTimerWhenMinimized.collectAsStateWithLifecycle(initialValue = true)
            val bleTxEnabled by
                viewModel.bleTxEnabled.collectAsStateWithLifecycle(initialValue = false)
            val bleFtmsDeviceName by
                viewModel.bleFtmsDeviceName.collectAsStateWithLifecycle(initialValue = "Grupetto FTMS")
            val showHeartRate by
                viewModel.showHeartRate.collectAsStateWithLifecycle(initialValue = viewModel.showHeartRate.value)
            val showCalories by
                viewModel.showCalories.collectAsStateWithLifecycle(initialValue = viewModel.showCalories.value)
            val heartRateRememberedDevices by
                viewModel.heartRateDevices.collectAsStateWithLifecycle(initialValue = emptyList())
            val heartRateAvailableDevices by
                viewModel.heartRateAvailableDevices.collectAsStateWithLifecycle(initialValue = emptyList())
            val heartRateConnectedDevice by
                viewModel.heartRateConnectedDevice.collectAsStateWithLifecycle(initialValue = null)
            val heartRateConnectionState by
                viewModel.heartRateConnectionState.collectAsStateWithLifecycle(initialValue = HeartRateConnectionState.Idle)

            LaunchedEffect(showHeartRate) {
                if (showHeartRate) {
                    viewModel.onRefreshHeartRateDiscovery()
                }
            }

            StartServicePage(
                timerShownWhenMinimized = timerShownWhenMinimized,
                onTimerShownWhenMinimizedToggled = viewModel::onShowTimerWhenMinimizedClicked,
                bleTxEnabled = bleTxEnabled,
                onBleTxEnabledToggled = viewModel::onBleTxEnabledClicked,
                bleFtmsDeviceName = bleFtmsDeviceName,
                showHeartRate = showHeartRate,
                onShowHeartRateToggled = viewModel::onShowHeartRateClicked,
                showCalories = showCalories,
                onShowCaloriesToggled = viewModel::onShowCaloriesClicked,
                heartRateConnectionState = heartRateConnectionState,
                heartRateConnectedDevice = heartRateConnectedDevice,
                heartRateRememberedDevices = heartRateRememberedDevices,
                heartRateAvailableDevices = heartRateAvailableDevices,
                onConnectHeartRateDevice = viewModel::onConnectHeartRateDevice,
                onDisconnectHeartRateDevice = viewModel::onDisconnectHeartRateDevice,
                onForgetHeartRateDevice = viewModel::onForgetHeartRateDevice,
                onRefreshHeartRateDiscovery = viewModel::onRefreshHeartRateDiscovery,
                onClickedStartOverlay = viewModel::onStartServiceClicked,
                onClickedRestartApp = viewModel::onRestartClicked,
                onClickedRelease = viewModel::onClickedRelease,
                latestRelease = latestRelease
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
    showHeartRate: Boolean,
    onShowHeartRateToggled: (Boolean) -> Unit,
    showCalories: Boolean,
    onShowCaloriesToggled: (Boolean) -> Unit,
    heartRateConnectionState: HeartRateConnectionState,
    heartRateConnectedDevice: HeartRateDevice?,
    heartRateRememberedDevices: List<HeartRateDevice>,
    heartRateAvailableDevices: List<HeartRateDevice>,
    onConnectHeartRateDevice: (HeartRateDevice) -> Unit,
    onDisconnectHeartRateDevice: () -> Unit,
    onForgetHeartRateDevice: (HeartRateDevice) -> Unit,
    onRefreshHeartRateDiscovery: () -> Unit,
    onClickedStartOverlay: () -> Unit,
    onClickedRestartApp: () -> Unit,
    onClickedRelease: (Release) -> Unit,
    latestRelease: Release?
) {
    var showHeartRatePicker by remember { mutableStateOf(false) }

    LaunchedEffect(showHeartRate) {
        if (!showHeartRate) {
            showHeartRatePicker = false
        }
    }

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
    Spacer(modifier = Modifier.height(50.dp))
    Button(onClick = onClickedStartOverlay) {
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
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Look for '$bleFtmsDeviceName' in your fitness app's device list",
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

    Spacer(modifier = Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Show heart rate on overlay?",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Checkbox(checked = showHeartRate, onCheckedChange = onShowHeartRateToggled)
    }

    val heartRateStatusText = when {
        heartRateConnectedDevice != null -> "Connected: ${heartRateConnectedDevice.displayName}"
        heartRateConnectionState == HeartRateConnectionState.Connecting -> "Connecting to known device..."
        heartRateConnectionState == HeartRateConnectionState.Scanning -> "Scanning for known devices..."
        else -> "No device connected"
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = {
                if (!showHeartRate) {
                    onShowHeartRateToggled(true)
                }
                showHeartRatePicker = true
                onRefreshHeartRateDiscovery()
            }
        ) {
            Text("Manage monitors")
        }
        Text(
            text = heartRateStatusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Show calories on overlay?",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Checkbox(checked = showCalories, onCheckedChange = onShowCaloriesToggled)
    }

    if (showHeartRatePicker) {
        HeartRatePickerDialog(
            connectedDevice = heartRateConnectedDevice,
            rememberedDevices = heartRateRememberedDevices,
            availableDevices = heartRateAvailableDevices,
            onConnect = onConnectHeartRateDevice,
            onDisconnect = onDisconnectHeartRateDevice,
            onForget = onForgetHeartRateDevice,
            onRefreshDiscovery = onRefreshHeartRateDiscovery,
            onDismiss = { showHeartRatePicker = false }
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
                    append("Grupetto is up to date: ${latestRelease.tagName} • $formattedDate • ${latestRelease.friendlyName}")
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
        colors = ButtonDefaults.buttonColors(containerColor = ErrorColor),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeartRatePickerDialog(
    connectedDevice: HeartRateDevice?,
    rememberedDevices: List<HeartRateDevice>,
    availableDevices: List<HeartRateDevice>,
    onConnect: (HeartRateDevice) -> Unit,
    onDisconnect: () -> Unit,
    onForget: (HeartRateDevice) -> Unit,
    onRefreshDiscovery: () -> Unit,
    onDismiss: () -> Unit
) {
    val distinctRemembered = rememberedDevices.distinctBy { it.address.lowercase() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .widthIn(min = 520.dp, max = 720.dp)
                .heightIn(min = 420.dp, max = 820.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 28.dp, vertical = 24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = "Heart rate monitors",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                HeartRatePickerDialogContent(
                    connectedDevice = connectedDevice,
                    rememberedDevices = distinctRemembered,
                    availableDevices = availableDevices,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onForget = onForget,
                    onDismiss = onDismiss
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Close")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onRefreshDiscovery) {
                        Text("Refresh")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeartRatePickerDialogContent(
    connectedDevice: HeartRateDevice?,
    rememberedDevices: List<HeartRateDevice>,
    availableDevices: List<HeartRateDevice>,
    onConnect: (HeartRateDevice) -> Unit,
    onDisconnect: () -> Unit,
    onForget: (HeartRateDevice) -> Unit,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (connectedDevice != null) {
            Text(
                text = "Connected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            HeartRateDevicePickerItem(
                device = connectedDevice,
                primaryActionLabel = "Disconnect",
                onPrimaryAction = {
                    onDisconnect()
                    onDismiss()
                },
                secondaryActionLabel = "Forget",
                onSecondaryAction = { onForget(connectedDevice) }
            )
        }

        val filteredAvailable = availableDevices.filterNot { device ->
            device.address.equals(connectedDevice?.address, ignoreCase = true)
        }
        if (filteredAvailable.isNotEmpty()) {
            Text(
                text = "Available devices",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            filteredAvailable.forEach { device ->
                val forgetAction = rememberedDevices.firstOrNull { remembered ->
                    remembered.address.equals(device.address, ignoreCase = true)
                }?.let { { onForget(device) } }

                HeartRateDevicePickerItem(
                    device = device,
                    primaryActionLabel = "Connect",
                    onPrimaryAction = {
                        onConnect(device)
                        onDismiss()
                    },
                    secondaryActionLabel = if (forgetAction != null) "Forget" else null,
                    onSecondaryAction = forgetAction
                )
            }
        }

        val otherRemembered = rememberedDevices.filterNot { device ->
            connectedDevice?.address.equals(device.address, ignoreCase = true) ||
                availableDevices.any { it.address.equals(device.address, ignoreCase = true) }
        }
        if (otherRemembered.isNotEmpty()) {
            Text(
                text = "Previously connected",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            otherRemembered.forEach { device ->
                HeartRateDevicePickerItem(
                    device = device,
                    primaryActionLabel = null,
                    onPrimaryAction = null,
                    secondaryActionLabel = "Forget",
                    onSecondaryAction = { onForget(device) }
                )
            }
        }

        if (
            connectedDevice == null &&
            filteredAvailable.isEmpty() &&
            otherRemembered.isEmpty()
        ) {
            Text(
                text = "No monitors found yet. Ensure your monitor is on and tap Refresh.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun HeartRateDevicePickerItem(
    device: HeartRateDevice,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    secondaryActionLabel: String?,
    onSecondaryAction: (() -> Unit)?
) {
    val baseModifier = Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 60.dp)

    val interactionModifier = onPrimaryAction?.let {
        Modifier.clickable(role = Role.Button, onClick = it)
    } ?: Modifier

    Card(
        modifier = baseModifier.then(interactionModifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                modifier = Modifier.weight(1f),
                text = device.displayName.ifBlank { "Unknown Device" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!secondaryActionLabel.isNullOrBlank() && onSecondaryAction != null) {
                TextButton(onClick = onSecondaryAction) {
                    Text(secondaryActionLabel)
                }
            }

            if (!primaryActionLabel.isNullOrBlank() && onPrimaryAction != null) {
                TextButton(onClick = onPrimaryAction) {
                    Text(primaryActionLabel)
                }
            }
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

@Preview(showBackground = true, widthDp = 411)
@Composable
private fun StartServicePagePreview() {
    MaterialTheme {
        StartServicePage(
            timerShownWhenMinimized = true,
            onTimerShownWhenMinimizedToggled = {},
            bleTxEnabled = true,
            onBleTxEnabledToggled = {},
            bleFtmsDeviceName = "Grupetto FTMS",
            showHeartRate = true,
            onShowHeartRateToggled = {},
            showCalories = true,
            onShowCaloriesToggled = {},
            heartRateConnectionState = HeartRateConnectionState.Connected,
            heartRateConnectedDevice = HeartRateDevice(name = "Polar H10", address = "00:11:22:33:44:55"),
            heartRateRememberedDevices = listOf(
                HeartRateDevice(name = "Garmin HRM", address = "AA:BB:CC:DD:EE:FF")
            ),
            heartRateAvailableDevices = listOf(
                HeartRateDevice(name = "Wahoo TICKR", address = "11:22:33:44:55:66"),
                HeartRateDevice(name = null, address = "77:88:99:AA:BB:CC")
            ),
            onConnectHeartRateDevice = {},
            onDisconnectHeartRateDevice = {},
            onForgetHeartRateDevice = {},
            onRefreshHeartRateDiscovery = {},
            onClickedStartOverlay = {},
            onClickedRestartApp = {},
            onClickedRelease = {},
            latestRelease = null
        )
    }
}
