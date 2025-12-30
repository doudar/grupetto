package com.spop.poverlay.DataBase

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DBHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "Grupetto.db"
        private const val DATABASE_VERSION = 3
    }

    override fun onCreate(db: SQLiteDatabase) {
        val CreateLap = " CREATE TABLE IF NOT EXISTS  ActivityHeader (  " +
                "        ActivityHeaderID integer primary key autoincrement not null, " +
                " UserID integer, " +
                " Title text," +
                " Distance Real ," +
                "TrackTime Integer , " +
                "Notes Text, " +
                "StartTime Long , " +
                "AverageSpeed float , " +
                "MaxSpeed float , " +
                "TrackType Text, " +
                "Time Integer, " +
                " [AvgHeartRate] Integer, " +
                " [MaxHeartRate] Integer, " +
                " [CadanceRevolutions] Integer, " +
                "  [AvgMovingCadance] Integer," +
                " [MaxCadence] Integer, " +
                " [AvgSpinningCadance] Integer," +
                " [AvgPower] Integer, " +
                " [MaxPower] Integer, " +
                " [Calories] Integer " +
                ")"

        db.execSQL(CreateLap)

        val CreateLapLine = "CREATE TABLE IF NOT EXISTS  ActivityLine ( " +
                " ActivityLineID integer primary key autoincrement not null , " +
                " ActivityHeaderID Integer, " +
                "Time Integer, " +
                "Speed Float, " +
                " Distance Float," +
                " Cadance Integer, HeartRate Integer, ElapsedTime integer, Power Integer" +
                "  )"
        db.execSQL(CreateLapLine)

        val CreateStatCardPositions = "CREATE TABLE IF NOT EXISTS StatCardPosition ( " +
                " StatCardPositionID integer primary key autoincrement not null, " +
                " UserID Integer, " +
                " StatCardID Integer, " +
                " x Integer, " +
                " y Integer " +
                " )"
        db.execSQL(CreateStatCardPositions)

        val CreateUser = "CREATE TABLE IF NOT EXISTS  User ( " +
                " UserID integer primary key autoincrement not null, " +
                " Username text, " +
                " BLEid text, " +
                " BLEName text " +
                " ) "
        db.execSQL(CreateUser)

        try {
            db.execSQL("alter table ActivityHeader add column Calories Integer")
        } catch (e: Exception) { }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS StatCardPosition ( " +
                    " StatCardPositionID integer primary key autoincrement not null, " +
                    " UserID Integer, " +
                    " StatCardID Integer, " +
                    " x Integer, " +
                    " y Integer " +
                    " )")
        }
    }

    fun updateStatCardPosition(userId: Int, statCardId: Int, x: Int, y: Int) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("UserID", userId)
            put("StatCardID", statCardId)
            put("x", x)
            put("y", y)
        }
        val rowsAffected = db.update(
            "StatCardPosition",
            values,
            "UserID = ? AND StatCardID = ?",
            arrayOf(userId.toString(), statCardId.toString())
        )
        if (rowsAffected == 0) {
            db.insert("StatCardPosition", null, values)
        }
    }

    fun getStatCardPosition(userId: Int, statCardId: Int): Pair<Int, Int>? {
        val db = this.readableDatabase
        val cursor = db.rawQuery(
            "SELECT x, y FROM StatCardPosition WHERE UserID = ? AND StatCardID = ?",
            arrayOf(userId.toString(), statCardId.toString())
        )
        var result: Pair<Int, Int>? = null
        if (cursor.moveToFirst()) {
            result = Pair(
                cursor.getInt(cursor.getColumnIndexOrThrow("x")),
                cursor.getInt(cursor.getColumnIndexOrThrow("y"))
            )
        }
        cursor.close()
        return result
    }

    fun clearStatCardPositions(userId: Int) {
        val db = this.writableDatabase
        db.delete("StatCardPosition", "UserID = ?", arrayOf(userId.toString()))
    }

    fun insertActivityHeader(userID: Int?, title: String?, distance: Float?, trackTime: Int?, notes: String?, startTime: Long?, averageSpeed: Float?, maxSpeed: Float?, trackType: String?, time: Int?, avgHeartRate: Int?, maxHeartRate: Int?, cadanceRevolutions: Int?, avgMovingCadance: Int?, avgSpinningCadance: Int?, avgPower: Int?, maxPower: Int?): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("UserID", userID); put("Title", title); put("Distance", distance); put("TrackTime", trackTime); put("Notes", notes); put("StartTime", startTime); put("AverageSpeed", averageSpeed); put("MaxSpeed", maxSpeed); put("TrackType", trackType); put("Time", time); put("AvgHeartRate", avgHeartRate); put("MaxHeartRate", maxHeartRate); put("CadanceRevolutions", cadanceRevolutions); put("AvgMovingCadance", avgMovingCadance); put("AvgSpinningCadance", avgSpinningCadance); put("AvgPower", avgPower); put("MaxPower", maxPower)
        }
        return db.insert("ActivityHeader", null, values)
    }

    fun updateActivityHeader(activityHeaderID: Int, distance: Float?, trackTime: Int?, averageSpeed: Float?, maxSpeed: Float?, time: Int?, avgHeartRate: Int?, maxHeartRate: Int?, cadanceRevolutions: Int?, avgSpinningCadance: Int?, avgPower: Int?, maxPower: Int?, maxCadence: Int?, calories: Int?): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("Distance", distance); put("TrackTime", trackTime); put("AverageSpeed", averageSpeed); put("MaxSpeed", maxSpeed); put("Time", time); put("AvgHeartRate", avgHeartRate); put("MaxHeartRate", maxHeartRate); put("CadanceRevolutions", cadanceRevolutions); put("AvgSpinningCadance", avgSpinningCadance); put("AvgPower", avgPower); put("MaxPower", maxPower); put("MaxCadence", maxCadence); put("Calories", calories)
        }
        return db.update("ActivityHeader", values, "ActivityHeaderID = ?", arrayOf(activityHeaderID.toString()))
    }

    fun insertActivityLine(activityHeaderID: Int, time: Long, speed: Float?, distance: Float?, cadance: Int?, heartRate: Int?, elapsedTime: Int?, power: Int?): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put("ActivityHeaderID", activityHeaderID); put("Time", time); put("Speed", speed); put("Distance", distance); put("Cadance", cadance); put("HeartRate", heartRate); put("ElapsedTime", elapsedTime); put("Power", power)
        }
        return db.insert("ActivityLine", null, values)
    }

    fun insertUser(username: String?, bleID: String?, bleName: String?): Long {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("Username", username); put("BLEid", bleID); put("BLEName", bleName) }
        return db.insert("User", null, values)
    }

    fun updateUser(userID: Int, username: String?, bleID: String?, bleName: String?): Int {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("Username", username); put("BLEid", bleID); put("BLEName", bleName) }
        return db.update("User", values, "UserID = ?", arrayOf(userID.toString()))
    }

    fun updateHeartRateDeviceID(userID: Int, bleID: String?  ):Int {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("BLEid", bleID)  }
        return db.update("User", values, "UserID = ?", arrayOf(userID.toString()))
    }

    fun updateHeartRateDeviceName(userID: Int, bleName: String?  ):Int {
        val db = this.writableDatabase
        val values = ContentValues().apply { put("BLEName", bleName)  }
        return db.update("User", values, "UserID = ?", arrayOf(userID.toString()))
    }

    fun deleteUser(userID: Int): Int {
        val db = this.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM StatCardPosition WHERE UserID  = ?", arrayOf(userID.toString()))

            db.execSQL("DELETE FROM ActivityLine WHERE ActivityHeaderID IN (SELECT ActivityHeaderID FROM ActivityHeader WHERE UserID = ?)", arrayOf(userID.toString()))
            db.delete("ActivityHeader", "UserID = ?", arrayOf(userID.toString()))
            val result = db.delete("User", "UserID = ?", arrayOf(userID.toString()))
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun deleteActivity(activityHeaderID: Int): Int {
        val db = this.writableDatabase
        db.beginTransaction()
        try {
            db.delete("ActivityLine", "ActivityHeaderID = ?", arrayOf(activityHeaderID.toString()))
            val result = db.delete("ActivityHeader", "ActivityHeaderID = ?", arrayOf(activityHeaderID.toString()))
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    fun getUser(userID: Int): UserData? {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM User WHERE UserID = ?", arrayOf(userID.toString()))
        var user: UserData? = null
        if (cursor.moveToFirst()) {
            user = UserData(
                id = cursor.getInt(cursor.getColumnIndexOrThrow("UserID")),
                username = cursor.getString(cursor.getColumnIndexOrThrow("Username")),
                bleId = cursor.getString(cursor.getColumnIndexOrThrow("BLEid")),
                bleName = cursor.getString(cursor.getColumnIndexOrThrow("BLEName"))
            )
        }
        cursor.close()
        return user
    }

    fun getUserCount(): Int {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM User", null)
        var count = 0
        if (cursor.moveToFirst()) { count = cursor.getInt(0) }
        cursor.close()
        return count
    }

    data class UserData(val id: Int, val username: String?, val bleId: String?, val bleName: String?)
}
