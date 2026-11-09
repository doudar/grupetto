package com.spop.poverlay.calibration

import kotlin.math.expm1
import kotlin.math.sqrt

internal const val DefaultRoomTemperatureFahrenheit = 72.0
internal const val CurrentHeatEstimatorVersion = 2

/** Experimental aluminum brake-disc estimate, not a surface sensor. Hub is a latent thermal state. */
data class FlywheelTemperatureState(
    val celsius: Double = (DefaultRoomTemperatureFahrenheit - 32) / 1.8,
    val roomFahrenheit: Double = DefaultRoomTemperatureFahrenheit,
    val receivingPower: Boolean = false,
    val displayUnit: TemperatureUnit = TemperatureUnit.Fahrenheit,
    val hubCelsius: Double = celsius,
) {
    val fahrenheit get() = celsius * 1.8 + 32
}

internal data class ThermalCheckpoint(val version: Int, val celsius: Double, val roomFahrenheit: Double,
    val wallMillis: Long, val hubCelsius: Double? = null)

internal data class FlywheelNodes(val discCelsius: Double, val hubCelsius: Double)

/** Provisional effective capacities/conductances, not measured masses or material specifications.
 * Aluminum disc / steel hub is an explicit construction assumption. See docs/flywheel-temperature.md.
 * All raw brake heat enters the disc; the hub exchanges heat with the disc and room.
 */
internal object FlywheelThermalParameters {
    const val DiscCapacity = 2000.0 // J/K
    const val HubCapacity = 6000.0 // J/K; total remains 8000 J/K
    const val DiscAmbientConductance = 4.0 // W/K
    const val HubAmbientConductance = 4.0 // W/K
    const val DiscHubConductance = 8.0 // W/K; includes contact resistance
}

/** Exact constant-input solution of Cd*Td' = P-Hd*(Td-Ta)-G*(Td-Th),
 * Ch*Th' = G*(Td-Th)-Hh*(Th-Ta). Negative eigenvalues keep long gaps stable;
 * expm1 retains small-step precision. No substeps, clamping or fictitious heat at restart.
 */
internal fun thermalStep(nodes: FlywheelNodes, ambientCelsius: Double, watts: Double,
    seconds: Double): FlywheelNodes {
    require(nodes.discCelsius.isFinite() && nodes.hubCelsius.isFinite() && ambientCelsius.isFinite())
    require(watts.isFinite() && watts >= 0 && seconds.isFinite() && seconds >= 0)
    if (seconds == 0.0) return nodes
    val cd = FlywheelThermalParameters.DiscCapacity
    val ch = FlywheelThermalParameters.HubCapacity
    val hd = FlywheelThermalParameters.DiscAmbientConductance
    val hh = FlywheelThermalParameters.HubAmbientConductance
    val g = FlywheelThermalParameters.DiscHubConductance
    val a = (hd + g) / cd
    val b = g / cd
    val c = g / ch
    val d = (hh + g) / ch
    val gap = sqrt((a - d) * (a - d) + 4 * b * c)
    val fast = -(a + d + gap) / 2
    val slow = -(a * d - b * c) / -fast
    val fastDelta = expm1(fast * seconds)
    val mixedDelta = (expm1(slow * seconds) - fastDelta) / gap
    val discEquilibrium = ambientCelsius + watts / (hd + g * hh / (g + hh))
    val hubEquilibrium = ambientCelsius + (discEquilibrium - ambientCelsius) * g / (g + hh)
    val x = nodes.discCelsius - discEquilibrium
    val y = nodes.hubCelsius - hubEquilibrium
    return FlywheelNodes(
        nodes.discCelsius + fastDelta * x + mixedDelta * ((-a - fast) * x + b * y),
        nodes.hubCelsius + fastDelta * y + mixedDelta * (c * x + (-d - fast) * y))
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
        checkpoint?.takeIf { it.version in 1..CurrentHeatEstimatorVersion && validNode(it.celsius) &&
            validRoomTemperature(it.roomFahrenheit) && it.wallMillis > 0 &&
            (it.version == 1 || it.hubCelsius?.let(::validNode) == true) }?.let {
            // V1 has no gradient history: initialize both nodes to its saved estimate.
            // A backwards wall clock cannot imply heating or negative elapsed time.
            val elapsed = ((wallMillis.toDouble() - it.wallMillis) / 1000).coerceAtLeast(0.0)
            val cooled = thermalStep(FlywheelNodes(it.celsius, if (it.version == 1) it.celsius else it.hubCelsius!!),
                (it.roomFahrenheit - 32) / 1.8, 0.0, elapsed)
            state = FlywheelTemperatureState(cooled.discCelsius, it.roomFahrenheit,
                hubCelsius = cooled.hubCelsius)
        }
    }

    fun advance(nowMillis: Long): FlywheelTemperatureState {
        if (nowMillis < advancedAt) return state
        val endOfHeat = sampleAt?.let { minOf(nowMillis, it + 3000) } ?: advancedAt
        val heatMillis = (endOfHeat - advancedAt).coerceAtLeast(0)
        val totalMillis = nowMillis - advancedAt
        val ambient = (state.roomFahrenheit - 32) / 1.8
        val heated = thermalStep(nodes(), ambient, watts, heatMillis / 1000.0)
        val fresh = sampleAt?.let { nowMillis - it in 0..2999 } == true
        val cooled = thermalStep(heated, ambient, 0.0, (totalMillis - heatMillis) / 1000.0)
        state = state.copy(celsius = cooled.discCelsius, hubCelsius = cooled.hubCelsius,
            receivingPower = fresh)
        advancedAt = nowMillis
        return state
    }

    fun observe(nowMillis: Long, power: Double) {
        if (nowMillis < advancedAt || sampleAt?.let { nowMillis <= it } == true) return
        advance(nowMillis)
        if (!power.isFinite() || power !in 0.0..3000.0) {
            sampleAt = null; watts = 0.0
            state = state.copy(receivingPower = false)
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
            hubCelsius = state.hubCelsius + (fahrenheit - state.roomFahrenheit) / 1.8,
            roomFahrenheit = fahrenheit)
    }

    fun setDisplayUnit(unit: TemperatureUnit) {
        state = state.copy(displayUnit = unit)
    }

    private fun nodes() = FlywheelNodes(state.celsius, state.hubCelsius)
    fun checkpoint(wallMillis: Long) = ThermalCheckpoint(CurrentHeatEstimatorVersion, state.celsius,
        state.roomFahrenheit, wallMillis, state.hubCelsius)
    val latestObservationAt get() = sampleAt
}

internal fun validRoomTemperature(value: Double) = value.isFinite() && value in 32.0..110.0

private fun validNode(celsius: Double) = celsius.isFinite() && celsius in -100.0..500.0
