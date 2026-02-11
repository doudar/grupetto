package com.spop.poverlay.erg

import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import timber.log.Timber
import kotlin.math.abs

class ErgController(private val sensorInterface: SensorInterface) : CoroutineScope {

    override val coroutineContext = SupervisorJob() + Dispatchers.Default

    companion object {
        private const val CONTROL_LOOP_INTERVAL_MS = 100L
        private const val CONTROL_LOOP_INTERVAL_SEC = 0.1

        private const val DEFAULT_KP = 0.007
        private const val DEFAULT_KI = 0.0
        private const val DEFAULT_KD = 0.02

        private const val INTEGRAL_MIN = -30.0
        private const val INTEGRAL_MAX = 30.0

        private const val MIN_RESISTANCE = 0.0
        private const val MAX_RESISTANCE = 100.0
        private const val MAX_RESISTANCE_CHANGE_PER_ITERATION = 3.0

        private const val MIN_CADENCE_RPM = 25
        private const val POWER_DEADBAND_WATTS = 3.0

        // EMA alpha for ~2-second time constant at 10Hz: alpha = dt / (tau + dt) = 0.1 / (2.0 + 0.1)
        private const val POWER_EMA_ALPHA = 0.047619047619047616

        private const val MIN_TARGET_POWER = 25
        private const val MAX_TARGET_POWER = 1000
    }

    private var kp = DEFAULT_KP
    private var ki = DEFAULT_KI
    private var kd = DEFAULT_KD

    private var controlJob: Job? = null
    @Volatile
    private var targetPowerWatts = 100
    @Volatile
    private var active = false

    // PID state
    private var currentResistance = 0.0
    private var integralTerm = 0.0
    private var smoothedPower = 0.0
    private var previousSmoothedPower = 0.0
    private var isFirstIteration = true
    private var isSmoothedPowerInitialized = false

    fun enable(targetPowerWatts: Int) {
        val clamped = targetPowerWatts.coerceIn(MIN_TARGET_POWER, MAX_TARGET_POWER)
        this.targetPowerWatts = clamped
        resetPidState()
        active = true
        startControlLoop()
        Timber.d("ERG enabled: target=${clamped}W, PID gains: Kp=$kp, Ki=$ki, Kd=$kd")
    }

    fun disable() {
        if (!active) return
        Timber.d("ERG disabled (was: target=${targetPowerWatts}W)")
        active = false
        stopControlLoop()
    }

    fun setTargetPower(watts: Int) {
        val clamped = watts.coerceIn(MIN_TARGET_POWER, MAX_TARGET_POWER)
        if (clamped != targetPowerWatts) {
            val previousTarget = targetPowerWatts
            targetPowerWatts = clamped
            // Reset integral on large target changes to avoid windup overshoot
            if (abs(clamped - previousTarget) > 20) {
                integralTerm = 0.0
            }
            Timber.d("Target power set: ${clamped}W")
        }
    }

    fun isActive(): Boolean = active

    fun getTargetPower(): Int = targetPowerWatts

    private fun resetPidState() {
        integralTerm = 0.0
        previousSmoothedPower = 0.0
        isFirstIteration = true
        isSmoothedPowerInitialized = false
        smoothedPower = 0.0
    }

    private fun startControlLoop() {
        if (controlJob?.isActive == true) return
        controlJob = launch {
            // Initialize currentResistance from the sensor's current reading
            try {
                currentResistance = sensorInterface.resistance.first().toDouble()
            } catch (_: Exception) {
                currentResistance = 50.0
            }
            Timber.d("PID control loop started, initial resistance: ${currentResistance.toInt()}")

            while (isActive && active) {
                try {
                    executeControlLoop()
                } catch (e: Exception) {
                    Timber.e(e, "Error in PID control loop iteration")
                }
                delay(CONTROL_LOOP_INTERVAL_MS)
            }
        }
    }

    private fun stopControlLoop() {
        controlJob?.cancel()
        controlJob = null
    }

    private suspend fun executeControlLoop() {
        // Read current power and cadence from sensor flows
        val rawPower = try {
            sensorInterface.power.first().toDouble()
        } catch (_: Exception) {
            return
        }
        val cadence = try {
            sensorInterface.cadence.first().toDouble()
        } catch (_: Exception) {
            return
        }

        // Smooth power with EMA
        if (!isSmoothedPowerInitialized) {
            isSmoothedPowerInitialized = true
            smoothedPower = rawPower
        } else {
            smoothedPower = (rawPower * POWER_EMA_ALPHA) + ((1 - POWER_EMA_ALPHA) * smoothedPower)
        }

        // Low cadence guard: pause PID below 25 RPM, reset integral
        if (cadence < MIN_CADENCE_RPM) {
            integralTerm = 0.0
            Timber.v("Cadence too low (${cadence.toInt()} RPM), PID paused")
            return
        }

        val error = targetPowerWatts - smoothedPower
        val inDeadband = abs(error) < POWER_DEADBAND_WATTS

        // P-term
        val pTerm = kp * error

        // I-term (only accumulate outside deadband)
        if (!inDeadband) {
            integralTerm += ki * error * CONTROL_LOOP_INTERVAL_SEC
            integralTerm = integralTerm.coerceIn(INTEGRAL_MIN, INTEGRAL_MAX)
        }

        // D-term on measurement (prevents derivative kick on setpoint changes)
        val dTerm = if (isFirstIteration) {
            isFirstIteration = false
            0.0
        } else {
            (-kd * (smoothedPower - previousSmoothedPower)) / CONTROL_LOOP_INTERVAL_SEC
        }
        previousSmoothedPower = smoothedPower

        // Within deadband, only D-term acts (prevents oscillation)
        val output = if (inDeadband) dTerm else pTerm + integralTerm + dTerm

        // Rate-limit resistance change and clamp to valid range
        val resistanceChange = output.coerceIn(
            -MAX_RESISTANCE_CHANGE_PER_ITERATION,
            MAX_RESISTANCE_CHANGE_PER_ITERATION
        )
        val newResistance = (currentResistance + resistanceChange).coerceIn(
            MIN_RESISTANCE,
            MAX_RESISTANCE
        )

        val newResistanceInt = newResistance.toInt()
        if (newResistanceInt != currentResistance.toInt()) {
            sensorInterface.setResistance(newResistanceInt)
            Timber.d(
                "PID: target=$targetPowerWatts, power=${smoothedPower.toInt()} (raw=${rawPower.toInt()}), " +
                    "error=${error.toInt()}, P=${"%.2f".format(pTerm)}, I=${"%.2f".format(integralTerm)}, " +
                    "D=${"%.2f".format(dTerm)}, out=${"%.2f".format(output)}, resistance=$newResistanceInt%"
            )
        }

        currentResistance = newResistance
    }
}
