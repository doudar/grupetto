package com.spop.poverlay

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spop.poverlay.releases.Release
import com.spop.poverlay.ui.theme.ErrorColor
import com.spop.poverlay.ui.theme.LatoFontFamily
import kotlin.math.max
import kotlin.math.min

private data class UiScale(
                val value: Float
) {
        fun sp(base: Float) = (base * value).sp
        fun dp(base: Float) = (base * value).dp
}

@Composable
fun ConfigurationPage(viewModel: ConfigurationViewModel) {
    val showPermissionInfo by remember { viewModel.showPermissionInfo }
    val latestRelease by remember { viewModel.latestRelease }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthScale = maxWidth.value / 1200f
        val heightScale = maxHeight.value / 800f
        val rawScale = min(widthScale, heightScale)
        val uiScale = UiScale(max(0.58f, min(rawScale, 1.15f)))

        Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
        ) {
            if (showPermissionInfo) {
                PermissionPage(
                        onClickedGrantPermission = viewModel::onGrantPermissionClicked,
                        uiScale = uiScale
                )
            } else {
                val timerShownWhenMinimized by
                        viewModel.showTimerWhenMinimized.collectAsStateWithLifecycle(
                                initialValue = true
                        )
                val bleTxEnabled by
                        viewModel.bleTxEnabled.collectAsStateWithLifecycle(initialValue = false)
                val bleFtmsDeviceName by
                        viewModel.bleFtmsDeviceName.collectAsStateWithLifecycle(
                                initialValue = "Grupetto FTMS"
                        )
                val antPlusTxEnabled by
                        viewModel.antPlusTxEnabled.collectAsStateWithLifecycle(initialValue = false)
                val antPlusDeviceName by
                        viewModel.antPlusDeviceName.collectAsStateWithLifecycle(
                                initialValue = "Grupetto ANT+"
                        )
                StartServicePage(
                        timerShownWhenMinimized,
                        viewModel::onShowTimerWhenMinimizedClicked,
                        bleTxEnabled,
                        viewModel::onBleTxEnabledClicked,
                        bleFtmsDeviceName,
                        antPlusTxEnabled,
                        viewModel::onAntPlusTxEnabledClicked,
                        antPlusDeviceName,
                        viewModel::onAntPlusDeviceNameChanged,
                        uiScale,
                        viewModel::onStartServiceClicked,
                        viewModel::onRestartClicked,
                        viewModel::onClickedRelease,
                        latestRelease
                )
            }
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
        antPlusTxEnabled: Boolean,
        onAntPlusTxEnabledToggled: (Boolean) -> Unit,
        antPlusDeviceName: String,
        onAntPlusDeviceNameChanged: (String) -> Unit,
        uiScale: UiScale,
        onClickedStartOverlay: () -> Unit,
        onClickedRestartApp: () -> Unit,
        onClickedRelease: (Release) -> Unit,
        latestRelease: Release?
) {
    Text(
            text = "Grupetto: An overlay for your Peloton bike",
            fontSize = uiScale.sp(50f),
            fontWeight = FontWeight.Bold
    )
    Text(
            text = "Note: Not endorsed with, associated with, or supported by Peloton",
            fontSize = uiScale.sp(25f),
            fontStyle = FontStyle.Italic,
            fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(uiScale.dp(20f)))
    Text(
        text = "Nov 2025 Update with colored metrics, charts, and max",
        fontSize = uiScale.sp(18f),
        fontStyle = FontStyle.Italic,
        color = Color.Gray
    )
    Spacer(modifier = Modifier.height(uiScale.dp(110f)))
    Button(
            onClick = onClickedStartOverlay,
    ) {
        Text(
                text = "Click here to start the overlay",
                fontSize = uiScale.sp(30f),
                fontFamily = LatoFontFamily,
                fontWeight = FontWeight.Bold,
                fontStyle = FontStyle.Italic,
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "Show timer when the overlay minimized?", fontSize = uiScale.sp(20f))
        Checkbox(
                checked = timerShownWhenMinimized,
                onCheckedChange = onTimerShownWhenMinimizedToggled
        )
    }
    // BLE FTMS Settings
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
                text = "Enable BLE TX (Transmission)?",
                fontSize = uiScale.sp(22f),
                fontWeight = FontWeight.Bold
        )
        Checkbox(checked = bleTxEnabled, onCheckedChange = onBleTxEnabledToggled)
    }

    if (bleTxEnabled) {
        Card(
                modifier = Modifier.padding(horizontal = uiScale.dp(12f), vertical = uiScale.dp(6f)),
                elevation = uiScale.dp(6f),
                backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.14f)
        ) {
            Column(modifier = Modifier.padding(horizontal = uiScale.dp(16f), vertical = uiScale.dp(12f))) {
                Text(
                        text = "✅ BLE TX is enabled",
                        fontSize = uiScale.sp(16f),
                        fontWeight = FontWeight.Bold,
                        color = Color.Green
                )
                Spacer(modifier = Modifier.height(uiScale.dp(6f)))
                Text(
                        text = "Connect your fitness app to: $bleFtmsDeviceName",
                        fontSize = uiScale.sp(22f),
                        fontWeight = FontWeight.ExtraBold
                )
            }
        }
    } else {
        Spacer(modifier = Modifier.height(uiScale.dp(8f)))

        Text(
                text = "💡 Enable to broadcast bike data to apps like Zwift, TrainerRoad, etc.",
                fontSize = uiScale.sp(14f),
                color = Color.Gray
        )
    }

    Spacer(modifier = Modifier.height(uiScale.dp(20f)))

    // ANT+ Settings
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
                text = "Enable ANT+ TX (Transmission)?",
                fontSize = uiScale.sp(22f),
                fontWeight = FontWeight.Bold
        )
        Checkbox(checked = antPlusTxEnabled, onCheckedChange = onAntPlusTxEnabledToggled)
    }

    if (antPlusTxEnabled) {
        Card(
                modifier = Modifier.padding(horizontal = uiScale.dp(12f), vertical = uiScale.dp(6f)),
                elevation = uiScale.dp(6f),
                backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.14f)
        ) {
            Column(modifier = Modifier.padding(horizontal = uiScale.dp(16f), vertical = uiScale.dp(12f))) {
                Text(
                        text = "✅ ANT+ TX is enabled",
                        fontSize = uiScale.sp(16f),
                        fontWeight = FontWeight.Bold,
                        color = Color.Green
                )
                Spacer(modifier = Modifier.height(uiScale.dp(6f)))
                Text(
                        text = "ANT+ Device: $antPlusDeviceName",
                        fontSize = uiScale.sp(22f),
                        fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(uiScale.dp(6f)))
                Text(
                        text = "📡 Broadcasting power and cadence data via ANT+ protocol",
                        fontSize = uiScale.sp(14f),
                        color = Color.Gray
                )
            }
        }
    } else {
        Spacer(modifier = Modifier.height(uiScale.dp(8f)))

        Text(
                text = "📡 Enable to broadcast bike data via ANT+ (requires ANT+ Radio Service installed)",
                fontSize = uiScale.sp(14f),
                color = Color.Gray
        )
    }

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
                                fontSize = uiScale.sp(20f),
                                color = LocalContentColor.current
                        )
        ) { onClickedRelease(latestRelease) }
    }

    Spacer(modifier = Modifier.height(uiScale.dp(40f)))
    Button(
            onClick = onClickedRestartApp,
            colors = ButtonDefaults.buttonColors(backgroundColor = ErrorColor),
    ) {
        Text(
                text = "Restart Grupetto",
                fontSize = uiScale.sp(20f),
                fontStyle = FontStyle.Italic,
                color = Color.White
        )
    }
    Spacer(modifier = Modifier.height(uiScale.dp(10f)))

    Text(
            "Device: ${Build.DEVICE}\t" +
                    "SDK: ${Build.VERSION.RELEASE}\t" +
                    "OS Version: ${Build.FINGERPRINT}\t",
            color = LocalContentColor.current.copy(alpha = .5f)
    )
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
