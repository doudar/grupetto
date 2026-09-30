package com.spop.poverlay.sensor.interfaces

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks the additive [SensorInterface.deviceTypeFlow] contract: a fixed interface's
 * reactive device-type view emits its single [SensorInterface.deviceType]. Detection is
 * model-based and synchronous, so the chosen interface is fixed for the process lifetime.
 */
class SensorInterfaceDeviceTypeFlowTest {

    private fun fixed(type: DeviceType) = object : SensorInterface {
        override val power: Flow<Float> = flowOf(0f)
        override val cadence: Flow<Float> = flowOf(0f)
        override val resistance: Flow<Float> = flowOf(0f)
        override val deviceType: DeviceType = type
    }

    @Test
    fun `default deviceTypeFlow emits the fixed deviceType`() = runBlocking {
        assertEquals(DeviceType.Bike, fixed(DeviceType.Bike).deviceTypeFlow.first())
        assertEquals(DeviceType.Tread, fixed(DeviceType.Tread).deviceTypeFlow.first())
    }
}
