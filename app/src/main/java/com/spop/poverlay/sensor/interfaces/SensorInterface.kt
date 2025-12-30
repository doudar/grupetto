package com.spop.poverlay.sensor.interfaces

import android.content.Context
import com.spop.poverlay.util.calculateSpeedFromPelotonV1Power
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface SensorInterface {
    val power: Flow<Float>
    val cadence: Flow<Float>
    val resistance: Flow<Float>
    val speed
        get() = power.map(::calculateSpeedFromPelotonV1Power)


    fun setResistance(resistance: Float, context: Context) {

        val pbs:PelotonBikePlusSensorInterface = PelotonBikePlusSensorInterface(context)
        pbs.setResistance(resistance, context)
    }
}