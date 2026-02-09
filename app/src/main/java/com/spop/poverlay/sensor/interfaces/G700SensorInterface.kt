package com.spop.poverlay.sensor.interfaces

import android.content.Context
import android.os.IBinder
import com.spop.poverlay.sensor.g700.G700PowerSensor
import com.spop.poverlay.sensor.g700.G700ResistanceSensor
import com.spop.poverlay.sensor.g700.G700RpmSensor
import com.spop.poverlay.sensor.g700.getG700Binder
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlin.coroutines.CoroutineContext

/**
 * Sensor interface for the G700 CrossTrainer bike.
 * The G700 uses com.onepeloton.workoutservices.metrics.MetricsService
 * instead of the old com.onepeloton.affernetservice used by Bike+.
 * 
 * Based on research by stellarhopper in issue #38.
 */
class G700SensorInterface(val context: Context) : SensorInterface, CoroutineScope {
    companion object{
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
            val service = getG700Binder(context)
            binder.emit(service)
        }
    }

    override val coroutineContext: CoroutineContext
        get() = SupervisorJob()

    fun stop() {
        coroutineContext.cancelChildren()
    }

    override val power: Flow<Float>
        get() = binder.flatMapLatest {
            val powerSensor = G700PowerSensor(it)
            powerSensor.start()
            powerSensor.sensorValue
        }

    override val cadence: Flow<Float>
        get() = binder.flatMapLatest {
            val rpmSensor = G700RpmSensor(it)
            rpmSensor.start()
            rpmSensor.sensorValue
        }

    override val resistance: Flow<Float>
        get() = binder.flatMapLatest {
            val resistanceSensor = G700ResistanceSensor(it)
            resistanceSensor.start()
            resistanceSensor.sensorValue
        }
            .windowed(ResistanceMovingAverageWindowSize, 1, true) { readings ->
                // Resistance sensor occasionally spikes for a single reading
                // So take the least of the last few readings
                readings.minOf { it }
            }
}
