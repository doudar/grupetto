package com.spop.poverlay.sensor.interfaces

import com.spop.poverlay.util.calculateSpeedFromPelotonV1Power
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

interface SensorInterface {
    val power: Flow<Float>
    val cadence: Flow<Float>
    val resistance: Flow<Float>
    val heartRate: Flow<Float>
        get() = flowOf(Float.NaN)
    val speed
        get() = power.map(::calculateSpeedFromPelotonV1Power)
}