package com.spop.poverlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.lazy.items

class HistoryActivity : ComponentActivity() {
    private val activities = mutableStateListOf<ActivityData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HistoryScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshActivities()
    }

    private fun refreshActivities() {
        val updatedList = getActivities()
        activities.clear()
        activities.addAll(updatedList)
    }

    @Composable
    fun HistoryScreen() {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            item {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            items(activities) { activity ->
                ActivityItem(activity) {
                     val intent = Intent(this@HistoryActivity, HistoryActivityDetail::class.java)
                     intent.putExtra("ACTIVITY_HEADER_ID", activity.id)
                     startActivity(intent)
                }
                Divider()
            }
        }
    }

    @Composable
    fun ActivityItem(activity: ActivityData, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 8.dp)
        ) {
            Text(text = activity.title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                 Text(text = "Date: ${formatDate(activity.startTime)}")
                 Text(text = "Duration: ${formatDuration(activity.trackTime)}")
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(text = "Avg HR: ${activity.avgHeartRate ?: "-"}")
                Text(text = "Avg Power: ${activity.avgPower ?: "-"}")
            }
        }
    }

    private fun getActivities(): List<ActivityData> {
        val dbHelper = DBHelper(this)
        val gv = GlobalVariables(this)
        val userId = gv.UserIDGet()
        
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT ActivityHeaderID, Title, StartTime, TrackTime, AvgHeartRate, AvgPower FROM ActivityHeader WHERE UserID = ? ORDER BY StartTime DESC",
            arrayOf(userId.toString())
        )
        val activitiesList = mutableListOf<ActivityData>()
        while (cursor.moveToNext()) {
            activitiesList.add(
                ActivityData(
                    id = cursor.getInt(0),
                    title = cursor.getString(1) ?: "Unknown",
                    startTime = cursor.getLong(2),
                    trackTime  = cursor.getInt(3),
                    avgHeartRate = if (cursor.isNull(4)) null else cursor.getInt(4),
                    avgPower = if (cursor.isNull(5)) null else cursor.getInt(5)
                )
            )
        }
        cursor.close()
        return activitiesList
    }
    
    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    private fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    data class ActivityData(
        val id: Int,
        val title: String,
        val startTime: Long,
        val trackTime: Int,
        val avgHeartRate: Int?,
        val avgPower: Int?
    )
}
