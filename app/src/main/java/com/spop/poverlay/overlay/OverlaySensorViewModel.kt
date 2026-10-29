@file:OptIn(kotlinx.coroutines.InternalCoroutinesApi::class, kotlin.time.ExperimentalTime::class)

package com.spop.poverlay.overlay

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.spop.poverlay.MainActivity
import com.spop.poverlay.sensor.DeadSensorDetector
import com.spop.poverlay.sensor.interfaces.DeviceType
import com.spop.poverlay.sensor.heartrate.HeartRateManager
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.power.PowerDisplay
import com.spop.poverlay.util.alignedTickerFlow
import com.spop.poverlay.util.smoothSensorValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private const val MphToKph = 1.60934

enum class MetricType {
    POWER, CADENCE, RESISTANCE, SPEED, HEART_RATE, INCLINE
}

/**
 * The device-appropriate set of primary metric cards, in display order.
 *
 * A treadmill reports real speed and incline but always 0 for cadence/resistance,
 * and its power is only a rough derived approximation, so those cards are hidden.
 * A bike shows the full power/cadence/resistance/speed set. This is the single
 * source of truth for which metrics appear and which are selectable for the chart,
 * keeping the main and minimized overlays consistent. Heart rate is handled separately;
 * cycling-based calorie estimates are shown only on bikes.
 */
fun deviceMetrics(deviceType: DeviceType): List<MetricType> = when (deviceType) {
    DeviceType.Tread -> listOf(MetricType.SPEED, MetricType.INCLINE)
    DeviceType.Bike -> listOf(
        MetricType.POWER, MetricType.CADENCE, MetricType.RESISTANCE, MetricType.SPEED
    )
}

/** The metric shown on the chart by default: the device's primary metric. */
fun defaultMetricFor(deviceType: DeviceType): MetricType = deviceMetrics(deviceType).first()

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
    private val application: Application,
    private val sensorInterface: SensorInterface,
    private val deadSensorDetector: DeadSensorDetector,
    private val timerViewModel: OverlayTimerViewModel,
    override val coroutineContext: CoroutineContext,
) : CoroutineScope {
    val emulatedModel = (sensorInterface as? com.spop.poverlay.sensor.interfaces.EmulatedSensorInterface)?.model?.label
    val bikeControlState = sensorInterface.bikeControl?.state
        ?: MutableStateFlow(com.spop.poverlay.control.ControlState())
    fun shift(direction: Int) { sensorInterface.bikeControl?.shift(direction) }

    companion object {
        // The sensor does not necessarily return new value this quickly
        val UiUpdatePeriod = 250.milliseconds

        // Charts append a point on every other UI tick, so GraphMaxDataPoints still spans 150 s
        val GraphUpdatePeriod = 500.milliseconds
        private val UiTicksPerGraphPoint: Int = run {
            require(GraphUpdatePeriod.inWholeMilliseconds % UiUpdatePeriod.inWholeMilliseconds == 0L) {
                "GraphUpdatePeriod must be a multiple of UiUpdatePeriod"
            }
            (GraphUpdatePeriod.inWholeMilliseconds / UiUpdatePeriod.inWholeMilliseconds).toInt()
        }

        // Max number of points before data starts to shift
        const val GraphMaxDataPoints = 300

        // Reset max values after all metrics are zero for this duration
        val MaxResetTimeout = 5.minutes
    }


    // Reactive device type. Detection is platform-based and synchronous, so the interface
    // is fixed at construction and this flow emits a single, correct value; deriving the
    // metric set from it keeps the overlay's cards consistent with the chosen device.
    private val deviceType: StateFlow<DeviceType> =
        sensorInterface.deviceTypeFlow.stateIn(
            this, SharingStarted.Eagerly, sensorInterface.deviceType
        )

    // Device-appropriate metric set (single source of truth). See deviceMetrics().
    private val visibleMetrics: StateFlow<List<MetricType>> =
        deviceType.map(::deviceMetrics).stateIn(
            this, SharingStarted.Eagerly, deviceMetrics(sensorInterface.deviceType)
        )

    /** Default chart metric for this device (Power on a bike, Speed on a tread). */
    val defaultMetric: StateFlow<MetricType> =
        deviceType.map(::defaultMetricFor).stateIn(
            this, SharingStarted.Eagerly, defaultMetricFor(sensorInterface.deviceType)
        )

    /** Which primary metric cards to show, derived reactively from [visibleMetrics]. */
    val showPowerCard: StateFlow<Boolean> = visibleMetricFlow(MetricType.POWER)
    val showCadenceCard: StateFlow<Boolean> = visibleMetricFlow(MetricType.CADENCE)
    val showResistanceCard: StateFlow<Boolean> = visibleMetricFlow(MetricType.RESISTANCE)
    val showSpeedCard: StateFlow<Boolean> = visibleMetricFlow(MetricType.SPEED)

    // A treadmill reports incline; a bike does not, so only show the card for a Tread.
    val showInclineCard: StateFlow<Boolean> = visibleMetricFlow(MetricType.INCLINE)

    // True while a treadmill is active. Drives the overlay's tread-specific spatial
    // layout (incline left, chart center, speed right) mirroring the Tread's physical
    // controls; the bike layout is used otherwise.
    val isTread: StateFlow<Boolean> = deviceType.map { it == DeviceType.Tread }.stateIn(
        this, SharingStarted.Eagerly, sensorInterface.deviceType == DeviceType.Tread
    )

    private fun visibleMetricFlow(metric: MetricType): StateFlow<Boolean> =
        visibleMetrics.map { metric in it }.stateIn(
            this, SharingStarted.Eagerly, metric in visibleMetrics.value
        )

    //TODO: Move this logic to dialog view model
    private val mutableIsMinimized = MutableStateFlow(false)
    val isMinimized = mutableIsMinimized.asStateFlow()

    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    val errorMessage = mutableErrorMessage.asStateFlow()

    private val mutableSelectedMetric = MutableStateFlow(defaultMetric.value)
    val selectedMetric = mutableSelectedMetric.asStateFlow()

    fun onDismissErrorPressed() {
        mutableErrorMessage.tryEmit(null)
    }

    fun onOverlayPressed() {
        mutableIsMinimized.apply { value = !value }
    }

    fun minimizeOverlay() {
        mutableIsMinimized.value = true
    }

    fun setMinimized(minimized: Boolean) {
        mutableIsMinimized.value = minimized
    }

    fun onOverlayDoubleTap() {
        application.apply {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    fun onMetricSelected(metric: MetricType) {
        if (metric != MetricType.HEART_RATE && metric !in visibleMetrics.value) return
        launch(Dispatchers.Main.immediate) {
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

    // Session stats are accumulated at the raw sensor rate (for accurate time-weighting), but
    // only published to the UI-facing StateFlows below every UiUpdatePeriod. Publishing every
    // sample (~20+/s on a Bike+) recomposed the overlay and rebuilt its chart on nearly every
    // frame. The collector and the publish ticker share this serial dispatcher, so the
    // accumulators are never touched concurrently.
    private val statsDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var statsDirty = false
    private var maxPowerAcc = 0f
    private var maxCadenceAcc = 0f
    private var maxResistanceAcc = 0f
    private var maxSpeedAcc = 0f
    private var maxHeartRateAcc = 0f
    private var totalEnergyAcc = 0f // kilojoules
    private var totalDistanceAcc = 0f // miles

    // Max value tracking
    private val mutableMaxPower = MutableStateFlow(0f)
    private val mutableMaxCadence = MutableStateFlow(0f)
    private val mutableMaxResistance = MutableStateFlow(0f)
    private val mutableMaxSpeed = MutableStateFlow(0f)
    private val mutableMaxHeartRate = MutableStateFlow(0f)
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
    private var sumHeartRate = 0f // heart rate × time accumulator
    private var totalHeartRateTime = 0f // seconds with valid heart rate while moving
    private val mutableAvgSpeed = MutableStateFlow(0f)
    private val mutableAvgResistance = MutableStateFlow(0f)
    private val mutableAvgCadence = MutableStateFlow(0f)
    private val mutableAvgHeartRate = MutableStateFlow(0f)

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
    val maxHeartRate = mutableMaxHeartRate.asStateFlow()

    val totalEnergy = mutableTotalEnergy.asStateFlow() // kilojoules
    val totalDistance = mutableTotalDistance.asStateFlow() // miles

    val avgSpeed = mutableAvgSpeed.asStateFlow() // mph
    val avgResistance = mutableAvgResistance.asStateFlow()
    val avgCadence = mutableAvgCadence.asStateFlow()
    val avgHeartRate = mutableAvgHeartRate.asStateFlow()

    private fun updateSessionStats(power: Float, cadence: Float, resistance: Float, speed: Float, heartRate: Float) {
        val currentTime = System.currentTimeMillis()
        val deltaSeconds = (currentTime - lastUpdateTime) / 1000f
        lastUpdateTime = currentTime

        // Calories follow the timer: they accumulate while it has elapsed time and restart
        // when it is reset (the UI tick also clears them if the sensors are silent).
        if (timerViewModel.elapsedSeconds.value > 0L) {
            caloriesJoulesAcc += power * deltaSeconds
        } else {
            caloriesJoulesAcc = 0.0
        }

        // Check if all values are essentially zero (for 5-minute reset)
        val allZero = power < 1f && cadence < 1f && resistance < 1f && speed < 0.1f
        // Check if actively moving (for accumulating averages/totals and timer)
        val isCurrentlyMoving = cadence >= 1f || speed >= 0.1f

        // Update movement state for timer auto-start/pause (only emits on change)
        if (mutableIsMoving.value != isCurrentlyMoving) {
            mutableIsMoving.value = isCurrentlyMoving
        }

        var forcePublish = false
        if (allZero) {
            // Check if we've been at zero for longer than the timeout
            if (currentTime - lastNonZeroTime > MaxResetTimeout.inWholeMilliseconds) {
                // Reset all max values, session totals and averages
                maxPowerAcc = 0f
                maxCadenceAcc = 0f
                maxResistanceAcc = 0f
                maxSpeedAcc = 0f
                maxHeartRateAcc = 0f
                totalEnergyAcc = 0f
                totalDistanceAcc = 0f
                totalActiveTime = 0f
                sumSpeed = 0f
                sumResistance = 0f
                sumCadence = 0f
                sumHeartRate = 0f
                totalHeartRateTime = 0f
                forcePublish = true
                // Signal session reset for timer
                mutableSessionReset.value = currentTime
            }
        } else {
            // Update last non-zero time
            lastNonZeroTime = currentTime

            // Update max values (always, even when not moving)
            if (power > maxPowerAcc) maxPowerAcc = power
            if (cadence > maxCadenceAcc) maxCadenceAcc = cadence
            if (resistance > maxResistanceAcc) maxResistanceAcc = resistance
            if (speed > maxSpeedAcc) maxSpeedAcc = speed
            if (heartRate > maxHeartRateAcc) maxHeartRateAcc = heartRate

            // Only accumulate totals and averages when actively moving
            if (isCurrentlyMoving) {
                // Energy: power (watts) × time (seconds) = joules, divide by 1000 for kJ
                totalEnergyAcc += (power * deltaSeconds) / 1000f
                // Distance: speed (mph) × time (hours) = miles
                totalDistanceAcc += speed * (deltaSeconds / 3600f)

                // Accumulate time-weighted sums for averages
                totalActiveTime += deltaSeconds
                sumSpeed += speed * deltaSeconds
                sumResistance += resistance * deltaSeconds
                sumCadence += cadence * deltaSeconds
                if (heartRate > 0f) {
                    sumHeartRate += heartRate * deltaSeconds
                    totalHeartRateTime += deltaSeconds
                }
            }
        }

        if (forcePublish) {
            statsDirty = false
            publishSessionStats()
        } else {
            statsDirty = true
        }
    }

    private fun publishSessionStats() {
        mutableMaxPower.value = maxPowerAcc
        mutableMaxCadence.value = maxCadenceAcc
        mutableMaxResistance.value = maxResistanceAcc
        mutableMaxSpeed.value = maxSpeedAcc
        mutableMaxHeartRate.value = maxHeartRateAcc
        mutableTotalEnergy.value = totalEnergyAcc
        mutableTotalDistance.value = totalDistanceAcc
        mutableAvgSpeed.value = if (totalActiveTime > 0f) sumSpeed / totalActiveTime else 0f
        mutableAvgResistance.value = if (totalActiveTime > 0f) sumResistance / totalActiveTime else 0f
        mutableAvgCadence.value = if (totalActiveTime > 0f) sumCadence / totalActiveTime else 0f
        mutableAvgHeartRate.value = if (totalHeartRateTime > 0f) sumHeartRate / totalHeartRateTime else 0f
    }

    private val displayedPower = sensorInterface.displayPower
        .shareIn(this, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    private val displayWatts = displayedPower.map { it.watts }
    @Volatile private var latestPowerDisplay = PowerDisplay(Float.NaN)
    private val mutablePowerDisplay = MutableStateFlow(latestPowerDisplay)
    val powerDisplay = mutablePowerDisplay.asStateFlow()

    /**
     * One sensor stream, written by its collector off the main thread and read by the shared UI
     * tick. The readout shows the mean of the samples that arrived since the previous tick, so it
     * is as current as the latest sample without its sample-to-sample jitter; with no new samples
     * the readout keeps its value. [graphFresh] marks a new smoothed value for the slower chart
     * cadence. [graph] is null for streams that are not charted.
     */
    private class TickedMetric(val graph: SnapshotStateList<Float>?) {
        private var sum = 0.0
        private var count = 0
        /** Last value published to the readout. */
        @Volatile var displayed = 0f
        @Volatile var smoothed = 0f
        val graphFresh = AtomicBoolean(false)

        fun add(value: Float) = synchronized(this) {
            sum += value
            count++
        }

        /** Mean of the samples since the last call, or null if none arrived. */
        fun takeMean(): Float? = synchronized(this) {
            if (count == 0) return null
            val mean = (sum / count).toFloat()
            sum = 0.0
            count = 0
            mean
        }?.also { displayed = it }

        // Handed from the stats dispatcher to the main thread inside withContext, which orders it.
        var pendingGraphPoint = 0f
        var hasPendingGraphPoint = false
    }

    val powerGraph = mutableStateListOf<Float>()
    val cadenceGraph = mutableStateListOf<Float>()
    val resistanceGraph = mutableStateListOf<Float>()
    val speedGraph = mutableStateListOf<Float>()
    val heartRateGraph = mutableStateListOf<Float>()
    val inclineGraph = mutableStateListOf<Float>()

    private val powerMetric = TickedMetric(powerGraph)
    private val cadenceMetric = TickedMetric(cadenceGraph)
    private val resistanceMetric = TickedMetric(resistanceGraph)
    private val speedMetric = TickedMetric(speedGraph)
    private val inclineMetric = TickedMetric(inclineGraph)
    private val heartRateMetric = TickedMetric(heartRateGraph)
    private val graphedMetrics = arrayOf(
        powerMetric, cadenceMetric, resistanceMetric, speedMetric, inclineMetric, heartRateMetric
    )

    private val mutableRpmValue = MutableStateFlow(SensorValuePlaceholderText)
    val rpmValue = mutableRpmValue.asStateFlow()

    private val mutableResistanceValue = MutableStateFlow(SensorValuePlaceholderText)
    val resistanceValue = mutableResistanceValue.asStateFlow()

    private val mutableSpeedValue = MutableStateFlow(SensorValuePlaceholderText)
    val speedValue = mutableSpeedValue.asStateFlow()
    val speedLabel = useMph.map {
        if (it) {
            "mph"
        } else {
            "kph"
        }
    }

    private val mutableInclineValue = MutableStateFlow(SensorValuePlaceholderText)
    val inclineValue = mutableInclineValue.asStateFlow()

    private fun formatSpeed(mph: Float): String =
        "%.1f".format(if (useMph.value) mph else mph * MphToKph)

    fun onClickedSpeedUnit() {
        launch(Dispatchers.Main.immediate) {
            useMph.emit(!useMph.value)
        }
        // Show the new unit right away instead of waiting for the next speed sample. Written on
        // the stats dispatcher, after any in-flight UI tick, so a tick formatted with the old unit
        // can't overwrite it.
        launch(statsDispatcher) {
            if (mutableSpeedValue.value != SensorValuePlaceholderText) {
                mutableSpeedValue.value = formatSpeed(speedMetric.displayed)
            }
        }
    }

    // Calories (kcal) = Total Energy (Joules) / 4184 / Efficiency. Energy is integrated in
    // updateSessionStats while the timer has elapsed time, and restarts when the timer resets.
    private var caloriesJoulesAcc = 0.0
    private val mutableCaloriesValue = MutableStateFlow(SensorValuePlaceholderText)
    val caloriesValue = mutableCaloriesValue.asStateFlow()

    fun getGraphForMetric(metric: MetricType): List<Float> {
        return when (metric) {
            MetricType.POWER -> powerGraph
            MetricType.CADENCE -> cadenceGraph
            MetricType.RESISTANCE -> resistanceGraph
            MetricType.SPEED -> speedGraph
            MetricType.HEART_RATE -> heartRateGraph
            MetricType.INCLINE -> inclineGraph
        }
    }

    private fun collectInto(flow: Flow<Float>, metric: TickedMetric, averageReadout: Boolean = true) {
        launch(Dispatchers.Default) {
            if (metric.graph == null) {
                flow.collect { metric.add(it) }
            } else {
                flow.onEach { if (averageReadout) metric.add(it) }
                    .smoothSensorValue()
                    .collect {
                        metric.smoothed = it
                        metric.graphFresh.set(true)
                    }
            }
        }
    }

    private fun setupSensorCollection() {
        // Power already carries matching 900 ms bike buckets and source labels. Do not
        // average it again on UI ticks, which could mix corrected and pedal values.
        collectInto(displayWatts, powerMetric, averageReadout = false)
        collectInto(sensorInterface.cadence, cadenceMetric)
        collectInto(sensorInterface.resistance, resistanceMetric)
        collectInto(sensorInterface.speed, speedMetric)
        collectInto(sensorInterface.incline, inclineMetric)
        collectInto(HeartRateManager.heartRate.map { (it ?: 0).toFloat() }, heartRateMetric)
        launch(Dispatchers.Default) {
            displayedPower.collect { latestPowerDisplay = it }
        }
    }

    /**
     * Publishes every UI-facing value on one fixed tick. Separately sampled flows each fired on
     * their own 500 ms clock, so the overlay recomposed and redrew about a dozen times a second;
     * aligned, their updates land in the same frame.
     */
    private suspend fun publishUiTick(publishGraphs: Boolean) {
        if (statsDirty) {
            statsDirty = false
            publishSessionStats()
        }
        if (timerViewModel.elapsedSeconds.value == 0L) {
            caloriesJoulesAcc = 0.0
        }
        mutableCaloriesValue.value =
            "%.0f".format(caloriesJoulesAcc / CaloriesPerJoule / CyclingEfficiency)

        // Readouts are StateFlows, so an unchanged string doesn't recompose anything.
        mutablePowerDisplay.value = latestPowerDisplay
        cadenceMetric.takeMean()?.let { mutableRpmValue.value = "%.0f".format(it) }
        resistanceMetric.takeMean()?.let { mutableResistanceValue.value = "%.0f".format(it) }
        speedMetric.takeMean()?.let { mutableSpeedValue.value = formatSpeed(it) }
        inclineMetric.takeMean()?.let { mutableInclineValue.value = "%.1f".format(it) }
        // The heart rate readout comes from HeartRateManager; drain this chart-only accumulator.
        heartRateMetric.takeMean()
        if (!publishGraphs) return
        var anyGraphPoint = false
        for (metric in graphedMetrics) {
            if (metric.graphFresh.getAndSet(false)) {
                metric.pendingGraphPoint = metric.smoothed
                metric.hasPendingGraphPoint = true
                anyGraphPoint = true
            }
        }
        if (anyGraphPoint) {
            withContext(Dispatchers.Main) {
                for (metric in graphedMetrics) {
                    if (!metric.hasPendingGraphPoint) continue
                    metric.hasPendingGraphPoint = false
                    val graph = metric.graph ?: continue
                    graph.add(metric.pendingGraphPoint)
                    if (graph.size > GraphMaxDataPoints) {
                        graph.removeFirst()
                    }
                }
            }
        }
    }

    private fun setupUiTick() {
        // The tick shares the stats dispatcher with the collector below, so the accumulators
        // are never touched concurrently. Publishing on a fixed tick also flushes the last
        // update even if the sensor stream goes quiet right after it.
        // The ticker is aligned with the ride timer's, so a timer update shares a frame with one.
        launch(statsDispatcher) {
            var tick = 0
            // Missed ticks are skipped: a burst of publishes after a stall would show nothing new.
            alignedTickerFlow(UiUpdatePeriod, catchUp = false).collect {
                tick = (tick + 1) % UiTicksPerGraphPoint
                publishUiTick(publishGraphs = tick == 0)
            }
        }
        // One sequential collector owns the accumulators, so no main-thread hop is needed.
        launch(statsDispatcher) {
            combine(
                displayWatts,
                sensorInterface.cadence,
                sensorInterface.resistance,
                sensorInterface.speed,
                HeartRateManager.heartRate.map { (it ?: 0).toFloat() }
            ) { power, cadence, resistance, speed, heartRate ->
                updateSessionStats(power, cadence, resistance, speed, heartRate)
            }.collect()
        }
    }

    // Happens last to ensure initialization order is correct
    init {
        setupSensorCollection()
        setupUiTick()

        // When the active device swaps (Bike->Tread), reset the chart selection if the
        // previously selected metric no longer applies to the new device. Heart rate is
        // gated separately (on a connected monitor), so leave that selection alone.
        launch(Dispatchers.Main.immediate) {
            deviceType.collect { type ->
                val selected = mutableSelectedMetric.value
                if (selected != MetricType.HEART_RATE && selected !in deviceMetrics(type)) {
                    mutableSelectedMetric.value = defaultMetricFor(type)
                }
            }
        }

        launch(Dispatchers.IO) {
            deadSensorDetector.deadSensorDetected.collect(object : FlowCollector<Unit> {
                override suspend fun emit(value: Unit) {
                    onDeadSensor()
                }
            })
        }

        launch(Dispatchers.IO) {
            errorMessage.collect(object : FlowCollector<String?> {
                override suspend fun emit(value: String?) {
                    // Leave minimized state if we're showing an error message
                    if (value != null && mutableIsMinimized.value) {
                        mutableIsMinimized.value = false
                    }
                }
            })
        }
    }
}

