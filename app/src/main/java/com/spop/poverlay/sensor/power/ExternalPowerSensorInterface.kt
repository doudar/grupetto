package com.spop.poverlay.sensor.power

import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** One selected watt stream for overlay, FTMS, cycling power, and ANT+; bike motion is unchanged. */
class ExternalPowerSensorInterface(
    private val bike: SensorInterface,
    readings: StateFlow<ExternalPowerReading?>,
    scope: CoroutineScope,
    clock: () -> Long
) : SensorInterface by bike {
    private val ticks = flow { while (true) { emit(clock()); delay(250) } }
    private val externalPower = combine(readings, ticks, ::freshExternalPower)
        .shareIn(scope, SharingStarted.Lazily, replay = 1)
    override val nativePower = bike.nativePower
    override val usesExternalPower = externalPower.map { it != null }.distinctUntilChanged()
    override val power = combine(bike.power, externalPower) { internal, external ->
        external ?: internal
    }.shareIn(scope, SharingStarted.Lazily, replay = 1)
    init { scope.launch { readings.collect { bike.bikeControl?.useExternalPower(it) } } }
}
