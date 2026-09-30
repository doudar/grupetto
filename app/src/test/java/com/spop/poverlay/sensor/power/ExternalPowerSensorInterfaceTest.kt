package com.spop.poverlay.sensor.power

import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class ExternalPowerSensorInterfaceTest {
    private var now = 1000L
    private val bike = object : SensorInterface {
        override val power = MutableStateFlow(145f)
        override val cadence = MutableStateFlow(80f)
        override val resistance = MutableStateFlow(40f)
        override val speed = MutableStateFlow(18f)
        override val bikeControl = BikeControl(true, { now }) { true }
    }
    private val readings = MutableStateFlow<ExternalPowerReading?>(null)
    private fun fixture(block: suspend (ExternalPowerSensorInterface) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(3000) { block(ExternalPowerSensorInterface(bike, readings, scope) { now }) } }
        finally { scope.cancel() }
    }
    @Test fun nativeInterfaceDefaultsDoNotChangeExistingBikeBehavior() = runBlocking {
        assertSame(bike.power, bike.nativePower)
        assertFalse(bike.usesExternalPower.first())
    }
    @Test fun selectsExternalWattsAndKeepsLivePelotonComparison() = fixture { sensor ->
        assertEquals(145f, sensor.power.first())
        readings.value = ExternalPowerReading(210, now, "meter")
        assertEquals(210f, sensor.power.first { it == 210f })
        assertTrue(sensor.usesExternalPower.first { it })
        assertEquals(145f, sensor.nativePower.first())
        bike.power.value = 160f
        assertEquals(160f, sensor.nativePower.first())
        assertEquals(210f, sensor.power.first())
        assertSame(bike.cadence, sensor.cadence)
        assertSame(bike.resistance, sensor.resistance)
        assertSame(bike.speed, sensor.speed)
        assertSame(bike.bikeControl, sensor.bikeControl)
    }
    @Test fun zeroIsValidAndDisconnectRestoresNativeAndHidesComparison() = fixture { sensor ->
        readings.value = ExternalPowerReading(0, now, "meter")
        assertEquals(0f, sensor.power.first())
        assertTrue(sensor.usesExternalPower.first())
        readings.value = null
        assertEquals(145f, sensor.power.first { it == 145f })
        assertFalse(sensor.usesExternalPower.first { !it })
    }
    @Test fun staleDataFallsBackEvenWithoutANewBikeOrMeterPacket() = fixture { sensor ->
        readings.value = ExternalPowerReading(210, now, "meter")
        assertEquals(210f, sensor.power.first())
        now += 3001
        assertEquals(145f, sensor.power.first { it == 145f })
        assertFalse(sensor.usesExternalPower.first { !it })
    }
    @Test fun freshPacketsBetweenTimerTicksNeverSwitchBackToNativePower() = fixture { sensor ->
        readings.value = ExternalPowerReading(210, now, "meter")
        val activeStates = mutableListOf<Boolean>()
        val watts = mutableListOf<Float>()
        val observers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            observers.launch { sensor.usesExternalPower.collect { activeStates.add(it) } }
            observers.launch { sensor.power.collect { watts.add(it) } }
            assertEquals(210f, sensor.power.first())
            // A newer packet arrives before the next 250 ms timer tick.
            now += 100
            readings.value = ExternalPowerReading(220, now, "meter")
            assertEquals(listOf(true), activeStates)
            assertEquals(listOf(210f, 220f), watts)
        } finally { observers.cancel() }
    }
    @Test fun sourceChangesReachTheSharedErgController() = fixture { sensor ->
        bike.bikeControl.acceptSample(BikeSample(145f, 80f, 40, now))
        assertTrue(bike.bikeControl.localErg(200))
        readings.value = ExternalPowerReading(210, now, "meter")
        assertEquals(ControlMode.Manual, sensor.bikeControl!!.state.first { it.mode == ControlMode.Manual }.mode)
    }
}
