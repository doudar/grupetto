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
        try { withTimeout(6000) { block(ExternalPowerSensorInterface(bike, readings, scope) { now }) } }
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
            sensor.power.first { it == 220f }
            assertEquals(listOf(true), activeStates)
            assertEquals(listOf(210f, 220f), watts)
        } finally { observers.cancel() }
    }
    @Test fun withoutAFreshMeterReadingBikeWattsAreNotRepeated() = fixture { sensor ->
        val watts = mutableListOf<Float>()
        val observers = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            observers.launch { sensor.power.collect { watts.add(it) } }
            // The initial bike value arrives only after its required 900 ms bucket.
            sensor.power.first()
            delay(700)
            assertEquals(listOf(145f), watts)
            // A stale reading falls back once, then the staleness check stops.
            readings.value = ExternalPowerReading(210, now, "meter")
            now += 3001
            delay(700)
            assertEquals(listOf(145f, 210f, 145f), watts)
        } finally { observers.cancel() }
    }
    @Test fun aNewSubscriberNeverSeesMeterWattsFromBeforeADisconnect() = fixture { sensor ->
        readings.value = ExternalPowerReading(210, now, "meter")
        assertEquals(210f, sensor.power.first())
        // Every subscriber has left; the meter disconnects before the next one arrives.
        readings.value = null
        assertEquals(145f, sensor.power.first())
        assertFalse(sensor.usesExternalPower.first())
    }
    @Test fun displayAndBroadcastShareOne900msBucketAndStopAfterTheLastSubscriber() = runBlocking<Unit> {
        val packets = MutableSharedFlow<PowerDisplay>()
        val packetBike = object : SensorInterface by bike {
            override val displayPower = packets
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(5000) {
            val sensor = ExternalPowerSensorInterface(packetBike, readings, scope) { now }
            val shown = MutableStateFlow<PowerDisplay?>(null)
            val broadcast = MutableStateFlow<Float?>(null)
            val displayObserver = scope.launch { sensor.displayPower.collect { shown.value = it } }
            val broadcastObserver = scope.launch { sensor.power.collect { broadcast.value = it } }
            packets.subscriptionCount.first { it == 1 }
            packets.emit(PowerDisplay(90f, DisplayPowerSource.Corrected, 100f))
            delay(100)
            // Pedal packets must not reweight the bike's two actual packets.
            readings.value = ExternalPowerReading(210, now, "meter")
            packets.emit(PowerDisplay(270f, DisplayPowerSource.Corrected, 300f))
            delay(400)
            assertNull(shown.value)
            assertNull(broadcast.value)
            val average = shown.filterNotNull().first()
            assertEquals(180f, average.watts)
            assertEquals(200f, average.nativeWatts)
            assertEquals(210f, average.pedalWatts)
            assertEquals(180f, broadcast.filterNotNull().first())
            assertEquals(1, packets.subscriptionCount.value)

            displayObserver.cancelAndJoin()
            assertEquals(1, packets.subscriptionCount.value)
            broadcastObserver.cancelAndJoin()
            packets.subscriptionCount.first { it == 0 }

            // Resubscription starts a fresh bucket, with no old correction or pedal replay.
            readings.value = null
            val restarted = scope.async { sensor.displayPower.first() }
            packets.subscriptionCount.first { it == 1 }
            packets.emit(PowerDisplay(50f))
            assertFalse(restarted.isCompleted)
            assertEquals(PowerDisplay(50f), restarted.await())
            packets.subscriptionCount.first { it == 0 }
        } } finally { scope.cancel() }
    }
    @Test fun sourceChangesReachTheSharedErgController() = fixture { sensor ->
        bike.bikeControl.acceptSample(BikeSample(145f, 80f, 40, now))
        assertTrue(bike.bikeControl.localErg(200))
        readings.value = ExternalPowerReading(210, now, "meter")
        assertEquals(ControlMode.Manual, sensor.bikeControl!!.state.first { it.mode == ControlMode.Manual }.mode)
    }
    @Test fun correctedDisplayAndBroadcastStayIdenticalWhilePedalsRemainTheComparison() = runBlocking {
        val display = MutableStateFlow(PowerDisplay(195f, DisplayPowerSource.Corrected, 145f))
        val correctedBike = object : SensorInterface by bike {
            override val power = display.map { it.watts }
            override val displayPower = display
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(6000) {
            val sensor = ExternalPowerSensorInterface(correctedBike, readings, scope) { now }
            // Model the persistent display/transport subscribers across source changes.
            scope.launch { sensor.displayPower.collect() }
            readings.value = ExternalPowerReading(210, now, "meter")
            val shown = sensor.displayPower.first { it.pedalWatts == 210f }
            assertEquals(195f, shown.watts)
            assertEquals("Corrected", shown.source.label)
            assertEquals("Pedals 210 W", shown.comparison)
            assertEquals(195f, sensor.power.first())

            display.value = PowerDisplay(202f, DisplayPowerSource.Corrected, 150f)
            assertEquals(202f, sensor.displayPower.first { it.watts == 202f }.watts)
            assertEquals(202f, sensor.power.first())

            // Off/unverified frames are explicitly marked Bike, so the display returns to pedals.
            display.value = PowerDisplay(150f)
            val fallback = sensor.displayPower.first { it.source == DisplayPowerSource.Pedals }
            assertEquals(210f, fallback.watts)
            assertEquals("Bike 150 W", fallback.comparison)
        } } finally { scope.cancel() }
    }

    @Test fun correctedDisplaySurvivesPedalTimeoutAndZeroRemainsAValidComparison() = runBlocking {
        val display = MutableStateFlow(PowerDisplay(0f, DisplayPowerSource.Corrected, 0f))
        val correctedBike = object : SensorInterface by bike {
            override val power = display.map { it.watts }
            override val displayPower = display
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try { withTimeout(3000) {
            val sensor = ExternalPowerSensorInterface(correctedBike, readings, scope) { now }
            readings.value = ExternalPowerReading(0, now, "meter")
            assertEquals("Pedals 0 W", sensor.displayPower.first { it.pedalWatts != null }.comparison)
            now += 3001
            val noPedals = sensor.displayPower.first { it.pedalWatts == null }
            assertEquals(DisplayPowerSource.Corrected, noPedals.source)
            assertEquals(0f, noPedals.watts)
            assertEquals("Bike 0 W", noPedals.comparison)
            display.value = PowerDisplay(145f)
            val raw = sensor.displayPower.first { it.source == DisplayPowerSource.Bike }
            assertEquals(145f, raw.watts)
            assertNull(raw.comparison)
        } } finally { scope.cancel() }
    }
}
