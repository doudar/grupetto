package com.spop.poverlay.antplus

import android.content.Context
import android.content.pm.PackageInfo
import com.spop.poverlay.sensor.interfaces.DeviceType
import com.spop.poverlay.sensor.interfaces.SensorInterface
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class AntPlusServerTest {
    private val context = mockk<Context>(relaxed = true)
    private val sensor = mockk<SensorInterface>()
    private val handler = mockk<AntPlusHandler>(relaxed = true)
    private val power = MutableStateFlow(300f)
    private val cadence = MutableStateFlow(90f)
    private val speed = MutableStateFlow(10f)
    private val hr = MutableStateFlow<Int?>(120)
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var server: AntPlusServer

    @Before fun setup() {
        every { sensor.deviceType } returns DeviceType.Bike
        every { sensor.power } returns power
        every { sensor.cadence } returns cadence
        every { sensor.speed } returns speed
        every { context.packageManager.getPackageInfo("com.dsi.ant.service.socket", 0) } returns mockk<PackageInfo>()
        server = AntPlusServer(context, sensor, hr) { scopes.add(it); handler }
    }

    @After fun cleanup() { server.stop() }

    @Test fun `repeated starts and stops own one session and allow restart`() {
        server.start()
        server.start()
        verify(exactly = 1) { handler.initialize() }
        assertTrue(scopes.single().isActive)
        server.stop()
        server.stop()
        verify(exactly = 1) { handler.shutdown() }
        assertFalse(scopes.single().isActive)
        server.start()
        verify(exactly = 2) { handler.initialize() }
        assertTrue(scopes.last().isActive)
    }

    @Test fun `failed initialization cleans up and can be tried again`() {
        every { handler.initialize() } throws IllegalStateException("bind failed")
        server.start()
        assertFalse(scopes.single().isActive)
        verify(exactly = 1) { handler.shutdown() }
        every { handler.initialize() } just Runs
        server.start()
        verify(exactly = 2) { handler.initialize() }
        assertTrue(scopes.last().isActive)
    }

    @Test fun `tread never starts cycling transmitter`() {
        every { sensor.deviceType } returns DeviceType.Tread
        assertFalse(server.isSupported)
        server.start()
        verify { handler wasNot Called; context wasNot Called }
        assertTrue(scopes.isEmpty())
    }

    @Test fun `telemetry converts mph to kmh and clears invalid samples`() = runBlocking {
        val samples = Channel<Float>(Channel.UNLIMITED)
        every { handler.broadcastSpeedData(any()) } answers { samples.trySend(firstArg()); Unit }
        server.start()
        assertEquals(16.0934f, withTimeout(5000) { samples.receive() }, 0.0001f)
        verify { handler.broadcastPowerData(300, 90) }
        power.value = Float.NaN
        cadence.value = -1f
        speed.value = Float.POSITIVE_INFINITY
        withTimeout(5000) { while (samples.receive() != 0f) { /* await sanitized sample */ } }
        verify { handler.broadcastPowerData(0, 0) }
    }

    @Test fun `HR disconnect sends zero instead of retaining last reading`() = runBlocking {
        val readings = Channel<Int>(Channel.UNLIMITED)
        every { handler.broadcastHrmData(any()) } answers { readings.trySend(firstArg()); Unit }
        server.start()
        assertEquals(120, withTimeout(5000) { readings.receive() })
        hr.value = null
        assertEquals(0, withTimeout(5000) { readings.receive() })
    }
}
