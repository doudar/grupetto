package com.spop.poverlay.sensor.v1legacy

import android.os.IBinder
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive

internal class SensorDataSensor(
    binder: IBinder,
    private val onNoTelemetry: suspend () -> Unit = {},
) : SensorInterface, AutoCloseable {
    private val client = SensorDataClient(binder)
    private fun readings() = MutableSharedFlow<Float>(
        replay = 1, extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val mutablePower = readings()
    private val mutableCadence = readings()
    private val mutableResistance = readings()
    override val power = mutablePower.asSharedFlow()
    override val cadence = mutableCadence.asSharedFlow()
    override val resistance = mutableResistance.asSharedFlow()

    /** Poll locally instead of registering repeating commands that could outlive Grupetto. */
    suspend fun run() {
        var receivedTelemetry = false
        var reportedFailure = false
        while (currentCoroutineContext().isActive) {
            var validReadings = 0
            for (read in SensorDataRead.entries) {
                val value = client.read(read)
                if (value != null) {
                    validReadings++
                    when (read) {
                        SensorDataRead.Cadence -> mutableCadence
                        SensorDataRead.Power -> mutablePower
                        SensorDataRead.Resistance -> mutableResistance
                    }.emit(value)
                }
                // No fabricated heartbeat on a failed read: DeadSensorDetector should still fire.
                delay(100)
            }
            if (validReadings > 0) {
                receivedTelemetry = true
                reportedFailure = false
            } else if (receivedTelemetry && !reportedFailure) {
                reportedFailure = true
                onNoTelemetry()
            }
            // Initial absence is diagnosed by the connection's startup timeout, after unbinding.
        }
    }

    override fun close() = client.close()
}
