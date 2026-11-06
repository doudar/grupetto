package com.spop.poverlay.calibration

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Process-owned ticker, independent of calibration Record/Activate and Activity visibility.
 * One small atomic preference snapshot every 30 seconds, never disk work on telemetry callbacks.
 */
class FlywheelTemperatureTracker(context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val preferences = context.getSharedPreferences("experimental-flywheel-temperature", Context.MODE_PRIVATE)
    private val lock = Any()
    private val model = FlywheelTemperatureModel(SystemClock.elapsedRealtime(), System.currentTimeMillis(),
        runCatching {
            val j = JSONObject(preferences.getString("checkpoint", null) ?: return@runCatching null)
            ThermalCheckpoint(j.getInt("version"), j.getDouble("celsius"), j.getDouble("roomFahrenheit"), j.getLong("wallMillis"))
        }.getOrNull())
    private val mutableState = MutableStateFlow(model.state)
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                synchronized(lock) {
                    mutableState.value = model.advance(SystemClock.elapsedRealtime())
                    if (++ticks % 30 == 0) persist()
                }
            }
        }
    }

    fun observe(frame: CalibrationFrame) = synchronized(lock) {
        model.observe(frame.timestamp, if (frame.powerSource == 1) frame.nativeWatts else Double.NaN)
    }

    fun setRoomTemperature(fahrenheit: Double) = synchronized(lock) {
        model.setRoomTemperature(fahrenheit, SystemClock.elapsedRealtime())
        mutableState.value = model.state
        persist()
    }

    private fun persist() {
        val s = model.checkpoint(System.currentTimeMillis())
        preferences.edit().putString("checkpoint", JSONObject().put("version", s.version)
            .put("celsius", s.celsius).put("roomFahrenheit", s.roomFahrenheit)
            .put("wallMillis", s.wallMillis).toString()).apply()
    }
}
