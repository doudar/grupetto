package com.spop.poverlay

import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.spop.poverlay.releases.Release
import com.spop.poverlay.ui.theme.ErrorColor
import com.spop.poverlay.ui.theme.LatoFontFamily
import com.spop.poverlay.HistoryActivity


@Composable
fun ConfigurationPage(
    viewModel: ConfigurationViewModel
) {
    val showPermissionInfo by remember { viewModel.showPermissionInfo }
    val latestRelease by remember { viewModel.latestRelease }
    val viewModel = viewModel

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (showPermissionInfo) {
            PermissionPage(viewModel::onGrantPermissionClicked)
        } else {
            val timerShownWhenMinimized by viewModel.showTimerWhenMinimized
                .collectAsStateWithLifecycle(
                    initialValue = true
                )

            val heartRateDeviceName by viewModel.heartRateDeviceName
                .collectAsStateWithLifecycle(initialValue = null)

            val currentUserName by viewModel.currentUserName
                .collectAsStateWithLifecycle(initialValue = "")

            val heartRate by viewModel.heartRate
                .collectAsStateWithLifecycle(initialValue = 0)

            StartServicePage(
                viewModel,
                timerShownWhenMinimized,
                viewModel::onShowTimerWhenMinimizedClicked,
                viewModel::onStartServiceClicked,
                viewModel::onRestartClicked,
                viewModel::onClickedRelease,
                latestRelease,
                heartRateDeviceName,
                heartRate,
                viewModel::onSelectHeartRateDeviceClicked,
                viewModel::onHistoryClicked,
                viewModel::onUserListClicked,
                currentUserName
            )
        }
    }
}


@Composable
private fun StartServicePage(
    viewModel: ConfigurationViewModel,
    timerShownWhenMinimized: Boolean,
    onTimerShownWhenMinimizedToggled: (Boolean) -> Unit,
    onClickedStartOverlay: () -> Unit,
    onClickedRestartApp: () -> Unit,
    onClickedRelease: (Release) -> Unit,
    latestRelease: Release?,
    heartRateDeviceName: String?,
    heartRate: Int,
    onSelectHeartRateDeviceClicked: () -> Unit,
    onHistoryClicked: () -> Unit,
    onUserListClicked: () -> Unit,
    currentUserName: String?
) {

    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {

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

        Spacer(modifier = Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {


            Column(Modifier.weight(1f).fillMaxSize(),


                horizontalAlignment = Alignment.CenterHorizontally ) {

                Text(text = "Hello $currentUserName", style = MaterialTheme.typography.headlineMedium,)
                Spacer(modifier = Modifier.height(10.dp))

                Button(onClick = onUserListClicked) {
                    Text("Switch / Edit User", fontSize = 20.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                }
                Spacer(modifier = Modifier.height(40.dp))
                Text(text = "Heart Rate Monitor", fontSize = 20.sp, fontWeight = FontWeight.Bold)


                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onSelectHeartRateDeviceClicked) {
                    Text("Change Device")
                }
                Text(text =  heartRateDeviceName ?: "No device selected", fontSize = 16.sp)
                if (heartRate > 0) {
                    Text(
                        text = "❤ $heartRate BPM",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                }
                else
                {
                    Text(
                        text = "❤ Device not connected",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                }
            }





            Column(modifier = Modifier.weight(3f)) {

                History(viewModel)
            }
        }


    }
    /*
        if (latestRelease == null) {
            Text(text = "Couldn't check for updates")
        } else {
            val formattedDate = DateUtils.getRelativeTimeSpanString(latestRelease.createdAt.time)
            val releaseText = if (latestRelease.isCurrentlyInstalled) {
                buildAnnotatedString {
                    "Grupetto is up to date: ${latestRelease.tagName} • $formattedDate • ${latestRelease.friendlyName}"
                }
            } else {
                buildAnnotatedString {
                    append("⭐ ")
                    withStyle(style = SpanStyle(textDecoration = TextDecoration.Underline)) {
                        append("New version released $formattedDate: ${latestRelease.friendlyName}.")
                    }
                }
            }
            ClickableText(
                text = releaseText,
                style = LocalTextStyle.current.copy(
                    fontSize = 20.sp,
                    color = LocalContentColor.current
                )
            ) {
                onClickedRelease(latestRelease)
            }
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

     */
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
    Button(
        onClick = onClickedGrantPermission
    ) {
        Text(text = "Grant Permission")
    }
}
