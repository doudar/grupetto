package com.spop.poverlay.sensor.interfaces

import android.content.Context
import com.spop.poverlay.sensor.v2.BikePlusCombinedSensor
import com.spop.poverlay.sensor.v2.getV2Binder
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlin.coroutines.CoroutineContext
import timber.log.Timber

class PelotonBikePlusSensorInterface(val context: Context, controlSupported: Boolean = false) : SensorInterface, CoroutineScope {
    companion object{
        /**
         * Resistance is filtered with a moving window since it occasionally spikes
         * The last few resistance readings will grouped, and the lowest reading will be shown
         *
         * The spikes are likely a limitation of ADC accuracy
         */
        const val ResistanceMovingAverageWindowSize = 3
    }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO
    @Volatile private var activeSensor: BikePlusCombinedSensor? = null
    override val bikeControl = com.spop.poverlay.control.BikeControl(
        controlSupported, android.os.SystemClock::elapsedRealtime
    ) { activeSensor?.setResistance(it) == true }

    init {
        if (controlSupported) {
            val preferences = context.getSharedPreferences(com.spop.poverlay.ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE)
            bikeControl.tune(preferences.getInt("bikeShiftSize", 2), preferences.getFloat("bikeProportionalGain", .007f))
            launch { while (isActive) { bikeControl.tick(); delay(100) } }
        }
    }

    fun stop() {
        bikeControl.stop()
        job.cancel()
    }

    private val combinedSensorState = flow {
        try {
            val binding = getV2Binder(context)
            try {
                val sensor = BikePlusCombinedSensor(binding.binder, bikeControl::acceptSample)
                activeSensor = sensor
                try {
                    sensor.start()
                    emit(sensor)
                    awaitCancellation()
                } finally {
                    activeSensor = null
                    sensor.stop()
                }
            } finally {
                binding.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to connect to bike sensor service")
        }
    }.shareIn(this, SharingStarted.Lazily, 1)
    override val power: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.power }

    override val cadence: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.cadence }

    override val resistance: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.resistance }
            .windowed(ResistanceMovingAverageWindowSize, 1, true) { readings ->
                // Resistance sensor occasionally spikes for a single reading
                // So take the least of the last few readings
                readings.minOf { it }
            }

    init {
        // Settings must detect the connected controllable bike even with all transmitters off.
        if (controlSupported) launch { power.collect {} }
    }

}
