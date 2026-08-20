package com.spop.poverlay.sensor.interfaces

import com.spop.poverlay.util.calculateSpeedFromPelotonV1Power
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Distinguishes the kind of Peloton hardware backing a [SensorInterface]. */
enum class DeviceType { Bike, Tread }

interface SensorInterface {
    val power: Flow<Float>
    val cadence: Flow<Float>
    val resistance: Flow<Float>
    val speed
        get() = power.map(::calculateSpeedFromPelotonV1Power)

    /** Incline (percent grade). Meaningful only for treadmills; bikes report none. */
    val incline: Flow<Float>
        get() = flowOf(0f)

    /** The kind of device backing this interface. Defaults to [DeviceType.Bike]. */
    val deviceType: DeviceType
        get() = DeviceType.Bike

    /**
     * Reactive view of [deviceType]. Fixed interfaces emit their single type. Detection
     * is model-based and synchronous, so the correct interface is chosen at construction
     * and the type never changes at runtime; this stays a Flow so the overlay can observe
     * it uniformly.
     */
    val deviceTypeFlow: Flow<DeviceType>
        get() = flowOf(deviceType)
}
