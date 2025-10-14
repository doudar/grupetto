package com.spop.poverlay.sensor.interfaces

import kotlinx.coroutines.flow.Flow

/**
 * Decorates an existing [SensorInterface] to supply heart rate data from an external source
 * (for example, a BLE monitor) without re-implementing the underlying bike metrics plumbing.
 */
class HeartRateAugmentedSensorInterface(
    private val delegate: SensorInterface,
    private val heartRateFlow: Flow<Float>
) : SensorInterface by delegate {
    override val heartRate: Flow<Float>
        get() = heartRateFlow
}
