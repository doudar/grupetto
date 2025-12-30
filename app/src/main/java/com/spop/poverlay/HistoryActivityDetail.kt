package com.spop.poverlay

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.TCX
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

import com.spop.poverlay.util.LineChartFull


class HistoryActivityDetail : ComponentActivity() {


    private var activityHeaderId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityHeaderId = intent.getIntExtra("ACTIVITY_HEADER_ID", -1)




        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (activityHeaderId != -1) {
                        ActivityDetailScreen(activityHeaderId)
                    } else {
                        Text("Error: Activity ID not found")
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Update database on pause/close is handled in the UI state save or we can do it here if we expose state
        // For simplicity, the UI will trigger updates
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    @Preview
    fun ActivityDetailScreen(headerId: Int) {
        val dbHelper = remember { DBHelper(this@HistoryActivityDetail) }
        var activityData by remember { mutableStateOf<ActivityHeaderData?>(null) }
        var lineData by remember { mutableStateOf<ActivityLineData?>(null) }
        var fullLineData by remember { mutableStateOf<List<ActivityLinePoint>>(emptyList()) }

        var title by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf("") }

        var showDeleteConfirmation by remember { mutableStateOf(false) }

        LaunchedEffect(headerId) {
            val header = getActivityHeader(dbHelper, headerId)
            activityData = header
            title = header?.title ?: ""
            notes = header?.notes ?: ""
            val lines = getActivityLines(dbHelper, headerId)
            lineData = lines.first
            fullLineData = lines.second
        }

        // Auto-save on change (debouncing could be added)
        LaunchedEffect(title, notes) {
            if (activityData != null) {
                updateActivityHeader(dbHelper, headerId, title, notes)
            }
        }

        if (showDeleteConfirmation) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirmation = false },
                title = { Text("Delete Activity") },
                text = { Text("Are you sure you want to delete this activity? This action cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            dbHelper.deleteActivity(headerId)
                            showDeleteConfirmation = false
                            close()
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (activityData == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val data = activityData!!
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(4f)) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally
                    )
                    {
                        val launcher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.RequestPermission(),
                            onResult = { isGranted: Boolean ->
                                if (isGranted) {
                                    exportToTcx(data, fullLineData)
                                } else {
                                    Toast.makeText(
                                        this@HistoryActivityDetail,
                                        "Permission Denied",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                                    if (ContextCompat.checkSelfPermission(
                                            this@HistoryActivityDetail,
                                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                        ) == PackageManager.PERMISSION_GRANTED
                                    ) {
                                        exportToTcx(data, fullLineData)
                                    } else {
                                        launcher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                    }
                                } else {
                                    exportToTcx(data, fullLineData)
                                }
                            }) {
                                Text("Export to TCX")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    showDeleteConfirmation = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { close() },
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
                }
                Row() {
                    Column(modifier = Modifier.width(300.dp)) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(text = "Start Time: ${formatDate(data.startTime)}")
                        Text(text = "Duration: ${formatDuration(data.trackTime)}")
                        Text(text = "Distance: ${"%.2f".format(data.distance)}")

                        Spacer(modifier = Modifier.height(16.dp))


                        Text("Avg HR: ${data.avgHeartRate}")
                        Text("Max HR: ${data.maxHeartRate}")
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Avg Power: ${data.avgPower}")
                        Text("Max Power: ${data.maxPower}")
                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Avg Cadence: ${data.avgSpinningCadance}")
                        Text("Max Cadence: ${data.maxCadence}")

                        Text("Calories: ${data.calories}")



                        Text("Avg Speed: ${"%.1f".format(data.averageSpeed)}")

                        Text("Max Speed: ${"%.1f".format(data.maxSpeed)}")

                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                    ) {

                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("Notes") },
                            modifier = Modifier
                                .fillMaxSize()

                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))


                Spacer(modifier = Modifier.height(24.dp))

                if (lineData != null) {
                    ChartSection(
                        "Power  Avg = ${data.avgPower} Max = ${data.maxPower}",
                        lineData!!.powerPoints,
                        Color.Green,
                        data.maxPower.toFloat(),
                        average = data.avgPower.toFloat()
                    )
                    if (data.maxHeartRate > 0) {
                        ChartSection(
                            "Heart  Rate Avg = ${data.avgHeartRate} Max = ${data.maxHeartRate}",
                            lineData!!.heartRatePoints,
                            Color.Red,
                            data.maxHeartRate.toFloat(),
                            average = data.avgHeartRate.toFloat()
                        )
                    }
                    ChartSection(
                        "Cadence   Avg = ${data.avgSpinningCadance}",
                        lineData!!.cadencePoints,
                        Color.Blue,
                        130f,
                        average = data.avgSpinningCadance.toFloat()
                    )
                    ChartSection(
                        "Speed Avg = ${"%.1f".format(data.averageSpeed)} Max = ${
                            "%.1f".format(
                                data.maxSpeed
                            )
                        }",
                        lineData!!.speedPoints,
                        Color.Cyan,
                        data.maxSpeed,
                        average = data.averageSpeed.toFloat()
                    )
                }
            }
        }
    }

    val MphToKph = 1.60934

    @Composable
    fun ChartSection(
        title: String,
        points: List<Float>,
        color: Color,
        maxValue: Float = 500f,
        average: Float = 0f
    ) {

        if (points.isNotEmpty()) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            LineChartFull(
                data = points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                lineColor = color,

                maxValue = maxValue,
                average = average,
                pauseChart = false
            )
            Spacer(modifier = Modifier.height(16.dp))
        }


    }

    private fun updateActivityHeader(dbHelper: DBHelper, id: Int, title: String, notes: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("Title", title)
            put("Notes", notes)
        }
        db.update("ActivityHeader", values, "ActivityHeaderID = ?", arrayOf(id.toString()))
    }

    private fun close() {
        finish()
    }

    private fun exportToTcx(header: ActivityHeaderData, lines: List<ActivityLinePoint>) {
        val tcx = TCX()
        val filepath = tcx.exportToTcx(activityHeaderId, this)


        val text = "A TCX file was exported to $filepath"
        val duration = Toast.LENGTH_SHORT

        val toast = Toast.makeText(this, text, duration)
        toast.show()
    }

    private fun getActivityHeader(dbHelper: DBHelper, id: Int): ActivityHeaderData? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT * FROM ActivityHeader WHERE ActivityHeaderID = ?",
            arrayOf(id.toString())
        )
        var data: ActivityHeaderData? = null
        if (cursor.moveToFirst()) {
            data = ActivityHeaderData(
                title = cursor.getString(cursor.getColumnIndexOrThrow("Title")),
                notes = cursor.getString(cursor.getColumnIndexOrThrow("Notes")),
                startTime = cursor.getLong(cursor.getColumnIndexOrThrow("StartTime")),
                trackTime = cursor.getInt(cursor.getColumnIndexOrThrow("TrackTime")),
                distance = cursor.getFloat(cursor.getColumnIndexOrThrow("Distance")),
                avgHeartRate = cursor.getInt(cursor.getColumnIndexOrThrow("AvgHeartRate")),
                maxHeartRate = cursor.getInt(cursor.getColumnIndexOrThrow("MaxHeartRate")),
                avgPower = cursor.getInt(cursor.getColumnIndexOrThrow("AvgPower")),
                maxPower = cursor.getInt(cursor.getColumnIndexOrThrow("MaxPower")),
                avgSpinningCadance = cursor.getInt(cursor.getColumnIndexOrThrow("AvgSpinningCadance")),
                averageSpeed = cursor.getFloat(cursor.getColumnIndexOrThrow("AverageSpeed")),
                maxSpeed = cursor.getFloat(cursor.getColumnIndexOrThrow("MaxSpeed")),
                maxCadence = cursor.getInt(cursor.getColumnIndexOrThrow("MaxCadence")),
                calories = cursor.getInt(cursor.getColumnIndexOrThrow("Calories"))

            )
        }
        cursor.close()
        return data
    }

    private fun getActivityLines(
        dbHelper: DBHelper,
        id: Int
    ): Pair<ActivityLineData, List<ActivityLinePoint>> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT Power, HeartRate, Cadance, Speed, Time, Distance FROM ActivityLine WHERE ActivityHeaderID = ? ORDER BY Time ASC",
            arrayOf(id.toString())
        )
        val power = mutableListOf<Float>()
        val hr = mutableListOf<Float>()
        val cadence = mutableListOf<Float>()
        val speed = mutableListOf<Float>()
        val fullData = mutableListOf<ActivityLinePoint>()

        while (cursor.moveToNext()) {
            val p = cursor.getInt(0)
            val h = cursor.getInt(1)
            val c = cursor.getInt(2)
            val s = cursor.getFloat(3)
            val t = cursor.getLong(4)
            val d = cursor.getFloat(5)

            power.add(p.toFloat())
            hr.add(h.toFloat())
            cadence.add(c.toFloat())
            speed.add(s)

            fullData.add(ActivityLinePoint(t, d, p, h, c, s))
        }
        cursor.close()
        return Pair(ActivityLineData(power, hr, cadence, speed), fullData)
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MM-dd-yyyy HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    data class ActivityHeaderData(
        val title: String?,
        val notes: String?,
        val startTime: Long,
        val trackTime: Int,
        val distance: Float,
        val avgHeartRate: Int,
        val maxHeartRate: Int,
        val avgPower: Int,
        val maxPower: Int,
        val avgSpinningCadance: Int,
        val averageSpeed: Float,
        val maxSpeed: Float,
        val maxCadence: Int,
        val calories: Int = 0
    )

    data class ActivityLineData(
        val powerPoints: List<Float>,
        val heartRatePoints: List<Float>,
        val cadencePoints: List<Float>,
        val speedPoints: List<Float>
    )

    data class ActivityLinePoint(
        val time: Long,
        val distance: Float,
        val power: Int,
        val heartRate: Int,
        val cadence: Int,
        val speed: Float
    )
}
