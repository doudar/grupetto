package com.spop.poverlay.sensor.interfaces

import android.content.Context
import com.spop.poverlay.sensor.tread.TreadCombinedSensor
import com.spop.poverlay.sensor.tread.getTreadBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.CoroutineContext
import timber.log.Timber

/**
 * Peloton Tread sensor interface. Mirrors [PelotonBikeSensorInterfaceV1New]:
 * binds the affernet tread interface, drives a [TreadCombinedSensor], and exposes
 * the scaled flows. Unlike the bike, speed and incline are real measured values,
 * so [speed] is overridden with the real flow rather than being derived from power.
 */
class PelotonTreadSensorInterface(val context: Context) : SensorInterface, CoroutineScope {

    private val job = SupervisorJob()

    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.IO

    override val deviceType: DeviceType
        get() = DeviceType.Tread

    fun stop() {
        job.cancel()
    }

    private val combinedSensorState = flow {
        // Acquire the binding only when collected, and retain ownership through cleanup.
        // There must be no suspension between a successful bind and entering try/finally.
        try {
            val binding = getTreadBinder(context)
            val sensor = TreadCombinedSensor(binding.binder, context, binding.connection)
            try {
                sensor.start()
                emit(sensor)
                awaitCancellation()
            } finally {
                sensor.stop()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to connect to tread service")
        }
    }.shareIn(this, SharingStarted.Lazily, 1)

    override val power: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.power }

    override val cadence: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.cadence }

    override val resistance: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.resistance }

    override val speed: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.speed }

    override val incline: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.incline }
}
