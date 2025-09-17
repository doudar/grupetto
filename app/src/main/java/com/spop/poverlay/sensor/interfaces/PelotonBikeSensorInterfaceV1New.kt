package com.spop.poverlay.sensor.interfaces

import android.content.Context
import android.os.IBinder
import com.spop.poverlay.sensor.v1new.V1NewPowerSensor
import com.spop.poverlay.sensor.v1new.V1NewResistanceSensor
import com.spop.poverlay.sensor.v1new.V1NewRpmSensor
import com.spop.poverlay.sensor.v1new.getV1NewBinder
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
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

    override val power: Flow<Float>
        get() = binder.flatMapLatest { binder ->
            val powerSensor = V1NewPowerSensor(binder)
            powerSensor.start()
            powerSensor.sensorValue
        }

    override val cadence: Flow<Float>
        get() = binder.flatMapLatest { binder ->
            val rpmSensor = V1NewRpmSensor(binder)
            rpmSensor.start()
            rpmSensor.sensorValue
        }

    override val resistance: Flow<Float>
        get() = binder.flatMapLatest { binder ->
            val resistanceSensor = V1NewResistanceSensor(binder)
            resistanceSensor.start()
            resistanceSensor.sensorValue
        }
            .windowed(ResistanceMovingAverageWindowSize, 1, true) { readings ->
                // Resistance sensor occasionally spikes for a single reading
                // So take the least of the last few readings
                readings.minOf { it }
            }
}
