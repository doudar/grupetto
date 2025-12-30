package com.spop.poverlay.overlay.composables

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.spop.poverlay.R
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables
import com.spop.poverlay.overlay.RecordingState
import com.spop.poverlay.util.LineChartMovable
import android.widget.Toast;


@Composable
@Preview(
    showSystemUi = true,
    device = "spec:width=1920dp,height=1080dp,dpi=420,isRound=false,chinSize=0dp,orientation=landscape"
)
fun OverlayFullScreenContent(
    modifier: Modifier = Modifier,
    heartrateGraph: List<Float> = emptyList(),
    power: String = "-",
    rpm: String = "-",
    powerGraph: List<Float> = emptyList(),
    cadenceGraph: List<Float> = emptyList(),
    resistance: String = "-",
    speed: String = "-",
    speedLabel: String = "",
    heartRate: String = "-",
    activityAvgHeartRate: String = "0",
    activityAvgPower: String = "0",
    activityAvgCadence: String = "0",
    activityAvgSpeed: String = "-",
    activityDistance: String = "-",
    activityMaxSpeed: String = "-",
    activityMaxPower: String = "-",
    activityMaxCadence: String = "-",
    activityMaxHeartRate: String = "-",
    activityAvgPowerFloat: Float = 0f,
    activityAvgCadenceFloat: Float = 0f,
    activityAvgHeartRateFloat: Float = 0f,
    activityDistanceFloat: Float = 0f,
    activityCalories: String = "-",
    pauseChart: Boolean = false,
    activityDurationTime: String = "-",
    recordingState: RecordingState = RecordingState.Stopped,
    onExitToHomeScreen: () -> Unit = {},
    onSpeedClicked: () -> Unit = {},
    onChartClicked: () -> Unit = {},
    onRecordClicked: () -> Unit = {},
    onDoubleTap: () -> Unit = {},
    onStopClicked: () -> Unit = {},
    onStopConfirm: () -> Unit = {},
    onStopCancel: () -> Unit = {},
    onIncreaseResistance: () -> Unit = {},
    onDecreaseResistance: () -> Unit = {},
    batteryPCT: String = ""
) {
    val statCardModifier = Modifier
        .requiredWidth(135.dp)
        .requiredHeight(110.dp)
    var shrinkChart by remember { mutableStateOf(false) }
    var alpha by remember { mutableStateOf(1f) }

    val context = LocalContext.current
    val dbHelper = remember { DBHelper(context) }
    val globalVariables = remember { GlobalVariables(context) }
    var layoutKey by remember { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize())
    {
        Row(modifier = Modifier
            .weight(1f)
            .graphicsLayer(alpha = alpha)
            .clickable { onChartClicked() }, verticalAlignment = Alignment.Top)
        {


            Box(
                modifier = modifier
                    .fillMaxSize()
                    //.background(Color.Red)
                    .graphicsLayer(alpha = alpha)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onChartClicked() }

                        )
                    }
            ) {

                key(layoutKey) {


                    StatCardMovable(
                        "Distance",
                        activityDistance,
                        if (speedLabel == "mph") "mi" else "km",
                        0, 0, 0,
                        statCardModifier

                    )
                }
                key(layoutKey) {
                    StatCardMovable(
                        "Resistance",
                        resistance,
                        " ",
                        1, 0, 110, statCardModifier


                    )
                }
                key(layoutKey) {
                    StatCardMovable(
                        "Heart Rate",
                        heartRate,
                        "bpm",

                        2, 0, 330,
                        statCardModifier

                            .requiredWidth(135.dp),
                        true,
                        activityAvgHeartRate,
                        activityMaxHeartRate,
                        //batteryPCT = batteryPCT


                        )
                }
                key(layoutKey) {
                    StatCardMovable(
                        "Speed",
                        speed,
                        speedLabel,
                        3,

                        0, 440,
                        statCardModifier

                            .requiredWidth(135.dp),
                        averages = true,
                        avg = activityAvgSpeed,
                        max = activityMaxSpeed


                    )
                    key(layoutKey) {
                        StatCardMovable(
                            "Cadence",
                            rpm,
                            "rpm",
                            4,
                            offsetx = 135, 0,
                            statCardModifier,


                            true,
                            activityAvgCadence,
                            activityMaxCadence,
                        )
                    }
                    key(layoutKey) {
                        StatCardMovable(
                            "Power",
                            power,
                            "watts",
                            5, 130, 480,
                            statCardModifier,

                            true,
                            activityAvgPower,
                            max = activityMaxPower

                        )
                    }
                    key(layoutKey) {


                        StatCardMovable(
                            "Duration",
                            activityDurationTime,
                            "",
                            6, 0, 220,
                            statCardModifier

                        )
                    }
                    key(layoutKey) {


                        StatCardMovable(
                            "Calories",
                            activityCalories,
                            "kcal",
                            7, 125, 110,
                            statCardModifier

                        )
                    }
                    key(layoutKey) {
                        LineChartMovable(
                            average = activityAvgCadence.toFloat(),
                            data = cadenceGraph,
                            maxValue = 100f,
                            minValue = 50f,
                            pauseChart = pauseChart,
                            modifier = Modifier
                                .requiredWidth(750.dp)
                                .requiredHeight(100.dp)
                                .padding(horizontal = 10.dp)
                                .padding(bottom = 5.dp),
                            fillColor = Color.Blue,
                            lineColor = Color.Blue,
                            id = 8,
                            offsetx = 250,
                            offsety = 0
                        )
                    }

                    key(layoutKey) {
                        LineChartMovable(
                            data = powerGraph,
                            maxValue = 300f,
                            minValue = 50f,
                            pauseChart = pauseChart,
                            modifier = Modifier


                                .requiredWidth(750.dp)
                                .requiredHeight(100.dp)
                                .padding(horizontal = 10.dp)
                                .padding(bottom = 5.dp),
                            fillColor = Color(android.graphics.Color.parseColor("#33FF48")), // Greenish fill
                            lineColor = Color(android.graphics.Color.parseColor("#18D92B")), // Greenish line

                            average = activityAvgPower.toFloat(),
                            id = 9,
                            offsetx = 250,
                            offsety = 440

                        )
                    }
                    key(layoutKey) {
                        LineChartMovable(
                            data = heartrateGraph,
                            average = activityAvgHeartRate.toFloat(),
                            maxValue = 120f,
                            minValue = 50f,
                            pauseChart = pauseChart,
                            modifier = Modifier
                                .requiredWidth(750.dp)
                                .padding(top = 50.dp)
                                .requiredHeight(100.dp)
                                .padding(horizontal = 10.dp)
                                .padding(bottom = 5.dp),
                            fillColor = Color.Transparent, // Greenish fill
                            lineColor = Color.Red, // Greenish line


                            id = 10,
                            offsetx = 250,
                            offsety = 480

                        )
                    }

                }



                Box(modifier = Modifier.align(Alignment.TopEnd)) {

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

                        if (recordingState == RecordingState.Stopped) {
                            Row() {
                                Button(
                                    onClick = { onRecordClicked() },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                    modifier = Modifier.size(60.dp),
                                    contentPadding = PaddingValues(0.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(Color.Red, CircleShape)
                                    )
                                }
                                Button(
                                    onClick = onExitToHomeScreen,
                                    shape = RectangleShape,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                    modifier = Modifier.size(60.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    content = {
                                        // Specify the icon using the icon parameter
                                        Image(
                                            modifier = Modifier
                                                .background(Color.White)
                                                .requiredHeight(60.dp)
                                                .requiredWidth(60.dp)
                                                .align(Alignment.CenterVertically)
                                                .padding(vertical = 4.dp),
                                            painter = painterResource(id = R.drawable.exit),
                                            contentDescription = null,
                                        )

                                    }
                                )
                            }
                        }

                        if (recordingState == RecordingState.Recording) {
                            Button(
                                onClick = { onStopClicked() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                modifier = Modifier.size(60.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(Color.Black)
                                )
                            }
                        }

                        if (recordingState == RecordingState.Confirm) {
                            Column(modifier = Modifier.background(Color.White)) {
                                Text("Are you sure you want to stop recording this activity?")
                                Row() {
                                    Button(onClick = { onStopConfirm()
                                    Toast.makeText(context, "Recording stopped, a TCX File was created of this activity in Documents / Grupetto", Toast.LENGTH_LONG).show()

                                    }) {
                                        Text("Stop Recording")
                                    }
                                    Button(onClick = { onStopCancel() }) {
                                        Text("Continue Recording")
                                    }
                                }
                            }
                        }


                    }
                }


                // Reset Button in lower right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            val userId = globalVariables.UserIDGet()
                            dbHelper.clearStatCardPositions(userId)
                            layoutKey++ // Force recomposition of movables
                        },
                        containerColor = Color.White,
                        contentColor = Color.Black,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset Positions")
                    }
                }
            }

        }
        Slider(
            value = alpha,

            onValueChange = { alpha = it },
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .padding(horizontal = 32.dp, vertical = 8.dp)
               // .align(Alignment.BottomCenter)
        )

    }
}
