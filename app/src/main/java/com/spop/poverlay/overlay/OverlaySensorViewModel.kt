@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlin.time.ExperimentalTime::class, kotlinx.coroutines.FlowPreview::class)

package com.spop.poverlay.overlay

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spop.poverlay.MainActivity
import com.spop.poverlay.sensor.DeadSensorDetector
import com.spop.poverlay.sensor.SensorSnapshot
import com.spop.poverlay.sensor.SensorSnapshotRepository
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.util.smoothSensorValue
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val MphToKph = 1.60934

enum class MetricType {
    POWER, CADENCE, RESISTANCE, SPEED, HEART
}

/**
 * Calorie calculation constants using Gross Mechanical Efficiency (GME) method:
 * 
 * 1. Calculate mechanical work: Power (W) × Time (s) = Energy in Joules
 * 2. Convert to mechanical kcal: Joules / 4184
 * 3. Account for body efficiency: Metabolic kcal = Mechanical kcal / Efficiency
 * 
 * Cycling efficiency represents the ratio of mechanical power output to metabolic power input.
 * Research shows typical values:
 * - Recreational cyclists: ~20%
 * - Average trained cyclists: ~22% (used here)
 * - Well-trained/elite cyclists: ~25%
 * 
 * This matches industry-standard calculations used by Garmin, Wahoo, and other cycling computers.
 */
private const val CyclingEfficiency = 0.22 // 22% efficiency (typical for cycling)
private const val CaloriesPerJoule = 4184.0 // Joules per kcal (thermochemical calorie definition)

class OverlaySensorViewModel(
    application: Application,
    private val sensorInterface: SensorInterface,
    private val sensorSnapshotRepository: SensorSnapshotRepository,
    private val deadSensorDetector: DeadSensorDetector,
    private val timerViewModel: OverlayTimerViewModel
) : AndroidViewModel(application) {

    companion object {
        // The sensor does not necessarily return new value this quickly
        val GraphUpdatePeriod = 800.milliseconds

        // Rolling average window for power smoothing
        private const val PowerAverageWindowSize = 4

        // Max number of points before data starts to shift
        const val GraphMaxDataPoints = 300

        // Reset max values after all metrics are zero for this duration
        val MaxResetTimeout = 5.minutes
    }


    //TODO: Move this logic to dialog view model
    private val mutableIsMinimized = MutableStateFlow(false)
    val isMinimized = mutableIsMinimized.asStateFlow()

    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    val errorMessage = mutableErrorMessage.asStateFlow()

    private val mutableSelectedMetric = MutableStateFlow(MetricType.POWER)
    val selectedMetric = mutableSelectedMetric.asStateFlow()

    fun onDismissErrorPressed() {
        mutableErrorMessage.tryEmit(null)
    }

    fun onOverlayPressed() {
        mutableIsMinimized.apply { value = !value }
    }

    fun onOverlayDoubleTap() {
        getApplication<Application>().apply {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    fun onMetricSelected(metric: MetricType) {
        viewModelScope.launch {
            mutableSelectedMetric.emit(metric)
        }
    }

    private fun onDeadSensor() {
        mutableErrorMessage
            .tryEmit(
                "The sensors seem to have fallen asleep." +
                        " You may need to restart your Peloton by removing the" +
                        " power adapter momentarily to restore them."
            )
    }

    private var useMph = MutableStateFlow(true)

    // Max value tracking
    private val mutableMaxPower = MutableStateFlow(0f)
    private val mutableMaxCadence = MutableStateFlow(0f)
    private val mutableMaxResistance = MutableStateFlow(0f)
    private val mutableMaxSpeed = MutableStateFlow(0f)
    private var lastNonZeroTime = System.currentTimeMillis()

    // Session totals tracking
    private val mutableTotalEnergy = MutableStateFlow(0f) // kilojoules
    private val mutableTotalDistance = MutableStateFlow(0f) // miles
    private var lastUpdateTime = System.currentTimeMillis()

    // Session averages tracking (time-weighted)
    private var totalActiveTime = 0f // seconds
    private var sumSpeed = 0f // speed × time accumulator
    private var sumResistance = 0f // resistance × time accumulator
    private var sumCadence = 0f // cadence × time accumulator
    private val mutableAvgSpeed = MutableStateFlow(0f)
    private val mutableAvgResistance = MutableStateFlow(0f)
    private val mutableAvgCadence = MutableStateFlow(0f)

    // Movement tracking for timer auto-start/pause
    private val mutableIsMoving = MutableStateFlow(false)
    val isMoving = mutableIsMoving.asStateFlow()

    // Session reset signal (fires when 5-minute inactivity reset occurs)
    private val mutableSessionReset = MutableStateFlow(0L) // timestamp of last reset
    val sessionReset = mutableSessionReset.asStateFlow()

    val maxPower = mutableMaxPower.asStateFlow()
    val maxCadence = mutableMaxCadence.asStateFlow()
    val maxResistance = mutableMaxResistance.asStateFlow()
    val maxSpeed = mutableMaxSpeed.asStateFlow()

    val totalEnergy = mutableTotalEnergy.asStateFlow() // kilojoules
    val totalDistance = mutableTotalDistance.asStateFlow() // miles

    val avgSpeed = mutableAvgSpeed.asStateFlow() // mph
    val avgResistance = mutableAvgResistance.asStateFlow()
    val avgCadence = mutableAvgCadence.asStateFlow()

    private fun updateSessionStats(power: Float, cadence: Float, resistance: Float, speed: Float) {
        val currentTime = System.currentTimeMillis()
        val deltaSeconds = (currentTime - lastUpdateTime) / 1000f
        lastUpdateTime = currentTime

        // Check if all values are essentially zero (for 5-minute reset)
        val allZero = power < 1f && cadence < 1f && resistance < 1f && speed < 0.1f
        // Check if actively moving (for accumulating averages/totals and timer)
        val isCurrentlyMoving = cadence >= 1f || speed >= 0.1f

        // Update movement state for timer auto-start/pause
        if (mutableIsMoving.value != isCurrentlyMoving) {
            mutableIsMoving.value = isCurrentlyMoving
        }

        if (allZero) {
            // Check if we've been at zero for longer than the timeout
            if (currentTime - lastNonZeroTime > MaxResetTimeout.inWholeMilliseconds) {
                // Reset all max values and session totals
                mutableMaxPower.value = 0f
                mutableMaxCadence.value = 0f
                mutableMaxResistance.value = 0f
                mutableMaxSpeed.value = 0f
                mutableTotalEnergy.value = 0f
                mutableTotalDistance.value = 0f
                // Reset averages
                totalActiveTime = 0f
                sumSpeed = 0f
                sumResistance = 0f
                sumCadence = 0f
                mutableAvgSpeed.value = 0f
                mutableAvgResistance.value = 0f
                mutableAvgCadence.value = 0f
                // Signal session reset for timer
                mutableSessionReset.value = currentTime
            }
        } else {
            // Update last non-zero time
            lastNonZeroTime = currentTime

            // Update max values (always, even when not moving)
            if (power > mutableMaxPower.value) mutableMaxPower.value = power
            if (cadence > mutableMaxCadence.value) mutableMaxCadence.value = cadence
            if (resistance > mutableMaxResistance.value) mutableMaxResistance.value = resistance
            if (speed > mutableMaxSpeed.value) mutableMaxSpeed.value = speed

            // Only accumulate totals and averages when actively moving
            if (isCurrentlyMoving) {
                // Accumulate session totals
                // Energy: power (watts) × time (seconds) = joules, divide by 1000 for kJ
                mutableTotalEnergy.value += (power * deltaSeconds) / 1000f
                // Distance: speed (mph) × time (hours) = miles
                mutableTotalDistance.value += speed * (deltaSeconds / 3600f)

                // Accumulate time-weighted sums for averages
                totalActiveTime += deltaSeconds
                sumSpeed += speed * deltaSeconds
                sumResistance += resistance * deltaSeconds
                sumCadence += cadence * deltaSeconds

                // Update averages
                if (totalActiveTime > 0f) {
                    mutableAvgSpeed.value = sumSpeed / totalActiveTime
                    mutableAvgResistance.value = sumResistance / totalActiveTime
                    mutableAvgCadence.value = sumCadence / totalActiveTime
                }
            }
        }
    }

    private val smoothedPower = sensorInterface.power
        .windowed(PowerAverageWindowSize, 1, true) { readings ->
            readings.average().toFloat()
        }

    private val uiSensorSnapshot = sensorSnapshotRepository.uiSnapshot
    private val rawSensorSnapshot = sensorSnapshotRepository.snapshot

    val powerValue = uiSensorSnapshot
        .map { "%.0f".format(it.power) }

    val rpmValue = uiSensorSnapshot
        .map { "%.0f".format(it.cadence) }

    val resistanceValue = uiSensorSnapshot
        .map { "%.0f".format(it.resistance) }

    val speedValue = combine(
        uiSensorSnapshot.map { it.speed }, useMph
    ) { speed, isMph ->
        val value = if (isMph) {
            speed
        } else {
            speed * MphToKph
        }
        "%.1f".format(value)
    }
    val speedLabel = useMph.map {
        if (it) {
            "mph"
        } else {
            "kph"
        }
    }

    val heartRateValue = HeartRateManager.heartRate
        .map { bpm ->
            if (bpm != null && bpm > 0) bpm.toString() else SensorValuePlaceholderText
        }

    val heartAvailable = HeartRateManager.heartRate.map { it != null }

    // UI toggles: whether calories show fully on main overlay
    private val _showCaloriesOnMain = MutableStateFlow(true)
    val showCaloriesOnMain = _showCaloriesOnMain.asStateFlow()

    fun toggleShowCaloriesOnMain() {
        _showCaloriesOnMain.value = !_showCaloriesOnMain.value
    }


    // Peak and average heart rate tracking (session simple average)
    private val mutableHeartPeak = MutableStateFlow(0)
    private val mutableHeartAvg = MutableStateFlow(0)

    val heartPeakValue = mutableHeartPeak
        .map { v -> if (v > 0) v.toString() else SensorValuePlaceholderText }

    val heartAvgValue = mutableHeartAvg
        .map { v -> if (v > 0) v.toString() else SensorValuePlaceholderText }

    val heartPeakRaw = mutableHeartPeak.asStateFlow()

    fun onClickedSpeedUnit() {
        viewModelScope.launch {
            useMph.emit(!useMph.value)
        }
    }

    // Calculate calories burned by accumulating energy over time
    // Calories (kcal) = Total Energy (Joules) / 4184 / Efficiency
    private val accumulatedEnergy = MutableStateFlow(0.0)
    
    val caloriesValue = accumulatedEnergy.map { totalJoules ->
        val calories = totalJoules / CaloriesPerJoule / CyclingEfficiency
        "%.0f".format(calories)
    }
    
    private fun setupCaloriesAccumulation() {
        var lastUpdateTime = System.currentTimeMillis()
        
        viewModelScope.launch(Dispatchers.IO) {
            combine(
                rawSensorSnapshot.map { it.power },
                timerViewModel.elapsedSeconds
            ) { watts, seconds -> 
                Pair(watts, seconds)
            }.collect { (watts, elapsedSeconds) ->
                if (elapsedSeconds > 0) {
                    val currentTime = System.currentTimeMillis()
                    val deltaTimeSeconds = (currentTime - lastUpdateTime) / 1000.0
                    
                    // Energy = Power × Time (in joules)
                    val energyDelta = watts * deltaTimeSeconds
                    
                    accumulatedEnergy.value += energyDelta
                    lastUpdateTime = currentTime
                } else {
                    // Timer was reset
                    accumulatedEnergy.value = 0.0
                    lastUpdateTime = System.currentTimeMillis()
                }
            }
        }
    }

    val powerGraph = mutableStateListOf<Float>()
    val cadenceGraph = mutableStateListOf<Float>()
    val resistanceGraph = mutableStateListOf<Float>()
    val speedGraph = mutableStateListOf<Float>()
    val heartGraph = mutableStateListOf<Float>()

    fun getGraphForMetric(metric: MetricType): List<Float> {
        return when (metric) {
            MetricType.POWER -> powerGraph
            MetricType.CADENCE -> cadenceGraph
            MetricType.RESISTANCE -> resistanceGraph
            MetricType.SPEED -> speedGraph
            MetricType.HEART -> heartGraph
        }
    }

    private fun setupGraphData() {
        // Power graph
        viewModelScope.launch(Dispatchers.IO) {
            var wasMoving = true
            smoothedPower.smoothSensorValue()
                .sample(GraphUpdatePeriod)
                .combine(isMoving) { value, moving -> value to moving }
                .collect(object : FlowCollector<Pair<Float, Boolean>> {
                    override suspend fun emit(value: Pair<Float, Boolean>) {
                        val (sensorValue, moving) = value
                        val shouldAppend = moving || wasMoving != moving
                        wasMoving = moving
                        if (!shouldAppend) return
                        withContext(Dispatchers.Main) {
                            powerGraph.add(sensorValue)
                            if (powerGraph.size > GraphMaxDataPoints) {
                                powerGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Cadence graph
        viewModelScope.launch(Dispatchers.IO) {
            var wasMoving = true
            sensorInterface.cadence.smoothSensorValue()
                .sample(GraphUpdatePeriod)
                .combine(isMoving) { value, moving -> value to moving }
                .collect(object : FlowCollector<Pair<Float, Boolean>> {
                    override suspend fun emit(value: Pair<Float, Boolean>) {
                        val (sensorValue, moving) = value
                        val shouldAppend = moving || wasMoving != moving
                        wasMoving = moving
                        if (!shouldAppend) return
                        withContext(Dispatchers.Main) {
                            cadenceGraph.add(sensorValue)
                            if (cadenceGraph.size > GraphMaxDataPoints) {
                                cadenceGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Resistance graph
        viewModelScope.launch(Dispatchers.IO) {
            var wasMoving = true
            sensorInterface.resistance.smoothSensorValue()
                .sample(GraphUpdatePeriod)
                .combine(isMoving) { value, moving -> value to moving }
                .collect(object : FlowCollector<Pair<Float, Boolean>> {
                    override suspend fun emit(value: Pair<Float, Boolean>) {
                        val (sensorValue, moving) = value
                        val shouldAppend = moving || wasMoving != moving
                        wasMoving = moving
                        if (!shouldAppend) return
                        withContext(Dispatchers.Main) {
                            resistanceGraph.add(sensorValue)
                            if (resistanceGraph.size > GraphMaxDataPoints) {
                                resistanceGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Speed graph
        viewModelScope.launch(Dispatchers.IO) {
            var wasMoving = true
            sensorInterface.speed.smoothSensorValue()
                .sample(GraphUpdatePeriod)
                .combine(isMoving) { value, moving -> value to moving }
                .collect(object : FlowCollector<Pair<Float, Boolean>> {
                    override suspend fun emit(value: Pair<Float, Boolean>) {
                        val (sensorValue, moving) = value
                        val shouldAppend = moving || wasMoving != moving
                        wasMoving = moving
                        if (!shouldAppend) return
                        withContext(Dispatchers.Main) {
                            speedGraph.add(sensorValue)
                            if (speedGraph.size > GraphMaxDataPoints) {
                                speedGraph.removeFirst()
                            }
                        }
                    }
                })
        }

        // Heart rate graph
        viewModelScope.launch(Dispatchers.IO) {
            var wasMoving = true
            HeartRateManager.heartRate
                .map { bpm -> bpm?.toFloat() ?: 0f }
                .sample(GraphUpdatePeriod)
                .combine(isMoving) { value, moving -> value to moving }
                .collect(object : FlowCollector<Pair<Float, Boolean>> {
                    override suspend fun emit(value: Pair<Float, Boolean>) {
                        val (sensorValue, moving) = value
                        val shouldAppend = moving || wasMoving != moving
                        wasMoving = moving
                        if (!shouldAppend) return
                        withContext(Dispatchers.Main) {
                            heartGraph.add(sensorValue)
                            if (heartGraph.size > GraphMaxDataPoints) {
                                heartGraph.removeFirst()
                            }
                        }
                    }
                })
        }
    }

    private fun setupMaxTracking() {
        viewModelScope.launch(Dispatchers.IO) {
            rawSensorSnapshot.collect(object : FlowCollector<SensorSnapshot> {
                override suspend fun emit(value: SensorSnapshot) {
                    withContext(Dispatchers.Main) {
                        updateSessionStats(
                            value.power,
                            value.cadence,
                            value.resistance,
                            value.speed
                        )
                    }
                }
            })
        }
    }

    // Happens last to ensure initialization order is correct
    init {
        setupGraphData()
        setupCaloriesAccumulation()
        setupMaxTracking()
        viewModelScope.launch(Dispatchers.IO) {
            deadSensorDetector.deadSensorDetected.collect(object : FlowCollector<Unit> {
                override suspend fun emit(value: Unit) {
                    onDeadSensor()
                }
            })
        }

        viewModelScope.launch(Dispatchers.IO) {
            errorMessage.collect(object : FlowCollector<String?> {
                override suspend fun emit(value: String?) {
                    // Leave minimized state if we're showing an error message
                    if (value != null && mutableIsMinimized.value) {
                        mutableIsMinimized.value = false
                    }
                }
            })
        }

        // Track peak and average heart rate
        viewModelScope.launch(Dispatchers.IO) {
            var sum = 0L
            var count = 0L
            combine(HeartRateManager.heartRate, isMoving) { bpm, moving -> bpm to moving }
                .collect { (bpm, moving) ->
                    if (bpm != null && bpm > 0 && moving) {
                        // peak
                        if (bpm > mutableHeartPeak.value) mutableHeartPeak.value = bpm
                        // average (simple running average)
                        sum += bpm
                        count += 1
                        mutableHeartAvg.value = (sum / count).toInt()
                    }
                }
        }
    }
}

