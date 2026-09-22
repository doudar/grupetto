package com.spop.poverlay.sensor.interfaces

import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import com.spop.poverlay.sensor.v2.BikePlusCombinedSensor
import com.spop.poverlay.sensor.v2.getV2Binder
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

class PelotonBikePlusSensorInterface(val context: Context) : SensorInterface, CoroutineScope {
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

    private val binder = MutableSharedFlow<IBinder>(replay = 1)
    private var serviceConnection: ServiceConnection? = null

    init {
        launch(Dispatchers.IO) {
            try {
                val service = getV2Binder(context)
                serviceConnection = service.connection
                binder.emit(service.binder)
            } catch (e: Exception) {
                Timber.w(e, "Failed to connect to V2 service: ${e.message}")
            }
        }
    }

    fun stop() {
        job.cancel()
        serviceConnection?.let { connection ->
            runCatching { context.unbindService(connection) }
                .onFailure { Timber.w(it, "Failed to unbind V2 service") }
        }
        serviceConnection = null
    }

    private val combinedSensorState = binder.transformLatest { service ->
        val sensor = BikePlusCombinedSensor(service)
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