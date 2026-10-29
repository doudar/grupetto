package com.spop.poverlay.sensor.power

import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** Display and transports share one power stream; ERG feedback still receives raw pedal readings. */
class ExternalPowerSensorInterface(
    private val bike: SensorInterface,
    readings: StateFlow<ExternalPowerReading?>,
    scope: CoroutineScope,
    clock: () -> Long
) : SensorInterface by bike {
    // Re-check staleness every 250 ms only while a reading exists, and stop once it has gone
    // stale. A ticker running forever re-emitted the last bike watts to every consumer 4x a second
    // even with no meter paired. Each new packet restarts the check against the current clock, so
    // a fresh packet is never briefly mistaken for future/stale data.
    // Both shares stop when nothing listens, e.g. after the overlay quits, and drop their cached
    // value then, so a later subscriber never sees old meter watts from before a disconnect.
    private val externalPower = readings.flatMapLatest { reading ->
        if (reading == null) flowOf(null) else flow<Float?> {
            while (true) {
                val now = clock()
                emit(freshExternalPower(reading, now))
                if (now - reading.timestamp > ExternalPowerStaleMillis) break
                delay(250)
            }
        }
    }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    override val nativePower = bike.nativePower
    override val usesExternalPower = externalPower.map { it != null }.distinctUntilChanged()
    override val displayPower = combine(bike.displayPower.averagePowerBuckets(), externalPower, ::selectPowerDisplay)
        .shareIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)
    override val power = displayPower.map { it.watts }.distinctUntilChanged()
    init { scope.launch { readings.collect { bike.bikeControl?.useExternalPower(it) } } }
}
