package com.spop.poverlay.sensor

import com.spop.poverlay.sensor.interfaces.DeviceType
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CadenceWatchdogTest {
    @Test
    fun `tread activity follows belt speed with zero cadence`() = runBlocking {
        val sensor = object : SensorInterface {
            override val deviceType = DeviceType.Tread
            override val power = flowOf(0f)
            override val cadence = flowOf(0f)
            override val resistance = flowOf(0f)
            override val speed = flowOf(0f, 0.1f, 3.2f, 0f)
        }
        assertEquals(listOf(false, true, true, false), watchdogActivity(sensor).toList())
    }

    @Test
    fun `bike retains cadence threshold regardless of speed`() = runBlocking {
        val sensor = object : SensorInterface {
            override val power = flowOf(250f)
            override val cadence = flowOf(0f, 19f, 20f, 90f, 0f)
            override val resistance = flowOf(50f)
            override val speed = flowOf(20f)
        }
        assertEquals(listOf(false, false, true, true, false), watchdogActivity(sensor).toList())
    }
}
