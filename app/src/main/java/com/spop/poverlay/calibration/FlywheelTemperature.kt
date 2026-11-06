package com.spop.poverlay.calibration

import kotlin.math.exp
import kotlin.math.expm1

/** Experimental effective bulk temperature, not a surface sensor or a power correction. */
enum class TemperatureConfidence { AmbientStartAssumed, InterruptedHistory }

data class FlywheelTemperatureState(
    val celsius: Double = (65.0 - 32) / 1.8,
    val roomFahrenheit: Double = 65.0,
    val receivingPower: Boolean = false,
    val unobservedHistory: Boolean = true,
    val confidence: TemperatureConfidence = TemperatureConfidence.AmbientStartAssumed,
) {
    val fahrenheit get() = celsius * 1.8 + 32
}

internal data class ThermalCheckpoint(val version: Int, val celsius: Double, val roomFahrenheit: Double,
    val wallMillis: Long)

/** Provisional C=8000 J/K, H=8 W/K (fan assumed constant), heat input = raw bike watts.
 * Exact constant-input solution avoids timestep instability. Constants require ride measurements.
 */
internal fun thermalStep(celsius: Double, ambientCelsius: Double, watts: Double, seconds: Double): Double {
    require(celsius.isFinite() && ambientCelsius.isFinite() && watts.isFinite() && watts >= 0)
    require(seconds.isFinite() && seconds >= 0)
    val x = -seconds / 1000.0
    return ambientCelsius + (celsius - ambientCelsius) * exp(x) + watts / 8.0 * -expm1(x)
}

/** Monotonic runtime integration. A sample is held for at most 3 s; gaps assume no heat input.
 * No extrapolation of old power across process restarts, duplicate packets or missing telemetry.
 */
internal class FlywheelTemperatureModel(nowMillis: Long, wallMillis: Long,
    checkpoint: ThermalCheckpoint? = null) {
    var state = FlywheelTemperatureState()
        private set
    private var advancedAt = nowMillis
    private var sampleAt: Long? = null
    private var watts = 0.0

    init {
        checkpoint?.takeIf { it.version == 1 && it.celsius.isFinite() && it.celsius in -100.0..500.0 &&
            validRoomTemperature(it.roomFahrenheit) && it.wallMillis > 0 }?.let {
            // A backwards wall clock cannot imply heating or negative elapsed time.
            val elapsed = ((wallMillis.toDouble() - it.wallMillis) / 1000).coerceAtLeast(0.0)
            state = FlywheelTemperatureState(thermalStep(it.celsius, (it.roomFahrenheit - 32) / 1.8,
                0.0, elapsed), it.roomFahrenheit, unobservedHistory = true,
                confidence = TemperatureConfidence.InterruptedHistory)
        }
    }

    fun advance(nowMillis: Long): FlywheelTemperatureState {
        if (nowMillis < advancedAt) return state
        val endOfHeat = sampleAt?.let { minOf(nowMillis, it + 3000) } ?: advancedAt
        val heatMillis = (endOfHeat - advancedAt).coerceAtLeast(0)
        val totalMillis = nowMillis - advancedAt
        val ambient = (state.roomFahrenheit - 32) / 1.8
        val heated = thermalStep(state.celsius, ambient, watts, heatMillis / 1000.0)
        val fresh = sampleAt?.let { nowMillis - it in 0..2999 } == true
        state = state.copy(celsius = thermalStep(heated, ambient, 0.0, (totalMillis - heatMillis) / 1000.0),
            receivingPower = fresh, unobservedHistory = state.unobservedHistory || totalMillis > heatMillis,
            confidence = if (sampleAt != null && totalMillis > heatMillis) TemperatureConfidence.InterruptedHistory else state.confidence)
        advancedAt = nowMillis
        return state
    }

    fun observe(nowMillis: Long, power: Double) {
        if (nowMillis < advancedAt || sampleAt?.let { nowMillis <= it } == true) return
        advance(nowMillis)
        if (!power.isFinite() || power !in 0.0..3000.0) {
            sampleAt = null; watts = 0.0
            state = state.copy(receivingPower = false, unobservedHistory = true, confidence = TemperatureConfidence.InterruptedHistory)
            return
        }
        sampleAt = nowMillis; watts = power
        state = state.copy(receivingPower = true)
    }

    fun setRoomTemperature(fahrenheit: Double, nowMillis: Long) {
        require(validRoomTemperature(fahrenheit))
        advance(nowMillis)
        // A setup edit corrects the ambient baseline, rather than simulating a physical room change.
        // Preserve accumulated rise (including a negative offset), after advancing on the old baseline.
        state = state.copy(celsius = state.celsius + (fahrenheit - state.roomFahrenheit) / 1.8,
            roomFahrenheit = fahrenheit)
    }

    fun checkpoint(wallMillis: Long) = ThermalCheckpoint(1, state.celsius, state.roomFahrenheit, wallMillis)
    val latestObservationAt get() = sampleAt
}

internal fun validRoomTemperature(value: Double) = value.isFinite() && value in 32.0..110.0
