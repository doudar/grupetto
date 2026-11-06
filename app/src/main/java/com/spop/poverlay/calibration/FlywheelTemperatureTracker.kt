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
    private val recent = ArrayDeque<CalibrationTemperature>()
    private var lastOutputTemperature: CalibrationTemperature? = null
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
        if (model.latestObservationAt == frame.timestamp && model.state.receivingPower && frame.powerSource == 1 && frame.nativeWatts.isFinite() &&
            (recent.lastOrNull()?.let { frame.timestamp - it.estimatedAt >= 250 } != false)) {
            recent.addLast(CalibrationTemperature(model.state.celsius, model.state.roomFahrenheit,
                frame.timestamp, historyUnobserved = model.state.unobservedHistory, confidence = model.state.confidence))
            while (recent.size > 24) recent.removeFirst()
        }
    }

    /** Never label a past sample with a future estimate. Bounded history spans delayed capture polls. */
    fun temperatureAt(nativeAt: Long): CalibrationTemperature? = synchronized(lock) {
        recent.lastOrNull { nativeAt - it.estimatedAt in 0..1500 }
    }

    /** Output may hold the last applied estimate during a gap; recordings must never retimestamp it. */
    fun outputTemperatureAt(nativeAt: Long): CalibrationTemperature? = synchronized(lock) {
        temperatureAt(nativeAt)?.let { lastOutputTemperature = it }
        lastOutputTemperature
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
