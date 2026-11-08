package com.spop.poverlay.calibration

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-owned ticker, independent of calibration Record/Activate and Activity visibility.
 * One small atomic preference snapshot every 30 seconds, never disk work on telemetry callbacks.
 */
class FlywheelTemperatureTracker(context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val wallMillis: () -> Long = System::currentTimeMillis) {
    private val preferences = context.getSharedPreferences("experimental-flywheel-temperature", Context.MODE_PRIVATE)
    private val lock = Any()
    private val model = FlywheelTemperatureModel(elapsedMillis(), wallMillis(),
        decodeThermalCheckpoint(preferences.getString("checkpoint", null))).apply {
            setDisplayUnit(runCatching {
                TemperatureUnit.valueOf(preferences.getString("display-unit", null) ?: "")
            }.getOrDefault(TemperatureUnit.Fahrenheit))
        }
    private val mutableState = MutableStateFlow(model.state)
    private val recent = ArrayDeque<CalibrationTemperature>()
    private var lastOutputTemperature: CalibrationTemperature? = null
    private var outputBaselineAt = Long.MIN_VALUE
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                synchronized(lock) {
                    mutableState.value = model.advance(elapsedMillis())
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
                frame.timestamp))
            while (recent.size > 24) recent.removeFirst()
        }
    }

    /** Never label a past sample with a future estimate. Bounded history spans delayed capture polls. */
    fun temperatureAt(nativeAt: Long): CalibrationTemperature? = synchronized(lock) {
        recent.lastOrNull { nativeAt - it.estimatedAt in 0..1500 }
    }

    /** Output may hold the last applied estimate during a gap; recordings must never retimestamp it. */
    fun outputTemperatureAt(nativeAt: Long): CalibrationTemperature? = synchronized(lock) {
        temperatureAt(nativeAt)?.takeIf { it.estimatedAt > outputBaselineAt }?.let { lastOutputTemperature = it }
        lastOutputTemperature
    }

    fun setRoomTemperature(fahrenheit: Double) = synchronized(lock) {
        val now = elapsedMillis()
        val changed = fahrenheit != model.state.roomFahrenheit
        model.setRoomTemperature(fahrenheit, now)
        if (changed) {
            outputBaselineAt = now
            // Update live correction immediately, without rewriting the immutable packet history.
            lastOutputTemperature = CalibrationTemperature(model.state.celsius, model.state.roomFahrenheit,
                now)
        }
        mutableState.value = model.state
        persist()
    }

    fun setDisplayUnit(unit: TemperatureUnit) = synchronized(lock) {
        model.setDisplayUnit(unit)
        mutableState.value = model.state
        // Do not retimestamp a thermal checkpoint or mutate packet history for a display change.
        preferences.edit().putString("display-unit", unit.name).apply()
    }

    private fun persist() {
        val s = model.checkpoint(wallMillis())
        preferences.edit().putString("checkpoint", encodeThermalCheckpoint(s)).apply()
    }
}
