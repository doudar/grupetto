package com.spop.poverlay.DataBase

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone


import androidx.compose.foundation.layout.*

import androidx.compose.material3.*
import androidx.compose.runtime.*

import java.util.*

public class TCX {

    val MphToKph = 1.60934

    lateinit var dbHelper: DBHelper

    public fun exportToTcx(activityID: Int, context: Context): String {

        dbHelper = DBHelper(context)

        val header = getActivityHeader(dbHelper, activityID)


        val startTimeLong = header!!.startTime
        val startTime = Date(startTimeLong)
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        val fn = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        val formattedStartTime = sdf.format(startTime)

        val tcxContent = StringBuilder()
        tcxContent.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        tcxContent.append("<TrainingCenterDatabase xmlns=\"http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2\">\n")
        tcxContent.append("  <Activities>\n")
        tcxContent.append("    <Activity Sport=\"Indoor Cycling\">\n")
        tcxContent.append("      <Id>$formattedStartTime</Id>\n")
        tcxContent.append("      <Lap StartTime=\"$formattedStartTime\">\n")
        tcxContent.append("        <TotalTimeSeconds>${header.trackTime}</TotalTimeSeconds>\n")
        tcxContent.append("        <DistanceMeters>${header.distance * 1000 * 1.60934}</DistanceMeters>\n") // Assuming distance is in km
        tcxContent.append("        <Calories>0</Calories>\n") // Calories not available
        tcxContent.append("        <Intensity>Active</Intensity>\n")
        tcxContent.append("        <TriggerMethod>Manual</TriggerMethod>\n")
        tcxContent.append("        <Track>\n")

        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT Power, HeartRate, Cadance, Speed, Time, Distance FROM ActivityLine WHERE ActivityHeaderID = ? ORDER BY Time ASC",
            arrayOf(activityID.toString())
        )
        val power = mutableListOf<Float>()
        val hr = mutableListOf<Float>()
        val cadence = mutableListOf<Float>()
        val speed = mutableListOf<Float>()


        while (cursor.moveToNext()) {
            val p = cursor.getInt(0)
            val h = cursor.getInt(1)
            val c = cursor.getInt(2)
            val s = cursor.getFloat(3)
            val t = cursor.getLong(4)
            val d = cursor.getFloat(5)

            val pointTimeStr = sdf.format(t)




            tcxContent.append("          <Trackpoint>\n")
            tcxContent.append("            <Time>$pointTimeStr</Time>\n")
            tcxContent.append("            <DistanceMeters>${MphToKph * d * 1000}</DistanceMeters>\n") // Assuming km
            tcxContent.append("            <HeartRateBpm>\n")
            tcxContent.append("              <Value>${h.toString()}</Value>\n")
            tcxContent.append("            </HeartRateBpm>\n")
            tcxContent.append("            <Cadence>${c}</Cadence>\n")
            tcxContent.append("            <Extensions>\n")
            tcxContent.append("              <TPX xmlns=\"http://www.garmin.com/xmlschemas/ActivityExtension/v2\">\n")
            tcxContent.append("                <Speed>${MphToKph * c / 3.6}</Speed>\n") // kph to m/s
            tcxContent.append("                <Watts>${p}</Watts>\n")
            tcxContent.append("              </TPX>\n")
            tcxContent.append("            </Extensions>\n")
            tcxContent.append("          </Trackpoint>\n")
        }
        cursor.close()

        tcxContent.append("        </Track>\n")
        tcxContent.append("      </Lap>\n")
        tcxContent.append("    </Activity>\n")
        tcxContent.append("  </Activities>\n")
        tcxContent.append("</TrainingCenterDatabase>")


        val fileName = "activity_${fn.format(startTime)}.tcx"
        val downloadDir = android.os.Environment.getExternalStorageDirectory()
        val file = File(downloadDir, fileName)

        writeTextDocument(context, fileName, tcxContent.toString())

        return "Documents / Grupetto / $fileName"
    }


    fun writeTextDocument(context: Context, filename: String, content: String) {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            // Fallback for older APIs (less private but compatible if needed)
            @Suppress("DEPRECATION")


            MediaStore.Files.getContentUri("external")
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            // put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOCUMENTS + "/Grupetto"
                )
            }
        }

        try {
            val contentResolver = context.contentResolver
            val uri: Uri? = contentResolver.insert(collection, contentValues)

            uri?.let {
                contentResolver.openOutputStream(it)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                    // File successfully written, maybe show a Toast
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            // Handle the error (e.g., file not found, operation not permitted)
        }
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


    data class ActivityLinePoint(
        val time: Long,
        val distance: Float,
        val power: Int,
        val heartRate: Int,
        val cadence: Int,
        val speed: Float
    )

}