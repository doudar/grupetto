package com.spop.poverlay.sensor.interfaces

import android.content.Context
import android.os.IBinder
import com.spop.poverlay.sensor.v1new.V1NewCombinedSensor
import com.spop.poverlay.sensor.v1new.getV1NewBinder
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlin.coroutines.CoroutineContext
import timber.log.Timber

/**
 * New V1 sensor interface that uses callback-based approach instead of polling
 * This provides more efficient and responsive sensor data updates
 */
class PelotonBikeSensorInterfaceV1New(val context: Context) : SensorInterface, CoroutineScope {
    companion object {
        /**
         * Resistance is filtered with a moving window since it occasionally spikes
         * The last few resistance readings will grouped, and the lowest reading will be shown
         *
         * The spikes are likely a limitation of ADC accuracy
         */
        const val ResistanceMovingAverageWindowSize = 3
    }
    
    private val binder = MutableSharedFlow<IBinder>(replay = 1)

    init {
        launch(Dispatchers.IO) {
            try {
                val service = getV1NewBinder(context)
                binder.emit(service)
                Timber.d("V1New service connected successfully")
            } catch (e: Exception) {
                Timber.w(e, "Failed to connect to V1New service: ${e.message}")
                // Don't crash the app if the bike service isn't available
                // The sensor flows will handle this gracefully
            }
        }
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob()

    fun stop() {
        coroutineContext.cancelChildren()
    }

    private val combinedSensorState = binder.transformLatest { service ->
        val sensor = V1NewCombinedSensor(service)
        sensor.start()
        emit(sensor)
        try {
            awaitCancellation()
        } finally {
            sensor.stop()
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
}
