package com.spop.poverlay.control

import com.spop.poverlay.sensor.power.ExternalPowerReading
import org.junit.Assert.*
import org.junit.Test

class ExternalPowerControlTest {
    private var now = 1000L
    private var resistance = 40
    private val writes = mutableListOf<Int>()
    private val control = BikeControl(true, { now }) { resistance = it; writes.add(it); true }
    private fun meter(watts: Int = 250, address: String = "a") = control.useExternalPower(ExternalPowerReading(watts, now, address))
    private fun sample(power: Float = 100f, cadence: Float = 80f) {
        control.acceptSample(BikeSample(power, cadence, resistance, now))
        control.tick()
    }
    @Test fun externalPowerDrivesErgDespiteOppositeNativePower() {
        sample(); meter(); assertTrue(control.localErg(200))
        repeat(10) { now += 200; meter(); sample(100f) }
        assertTrue(resistance < 40) // Native 100 W would have increased resistance.
        assertEquals(ControlMode.Erg, control.state.value.mode)
    }
    @Test fun externalZeroIsNotTreatedAsMissingAndBikeCadenceStillGuardsMotor() {
        sample(); meter(0); control.localErg(200)
        repeat(10) { now += 200; meter(0); sample(400f, 0f) }
        assertTrue(writes.isEmpty())
        repeat(10) { now += 200; meter(0); sample(400f) }
        assertTrue(resistance > 40)
    }
    @Test fun connectingOrSwitchingOrDisconnectingStopsErg() {
        sample(); control.localErg(200); meter()
        assertEquals(ControlMode.Manual, control.state.value.mode)
        control.localErg(200); meter(address = "b")
        assertEquals(ControlMode.Manual, control.state.value.mode)
        control.localErg(200); control.useExternalPower(null)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.isEmpty())
    }
    @Test fun staleMeterDisarmsWithFreshBikeDataAndDoesNotAutomaticallyResume() {
        sample(); meter(); control.localErg(200)
        now += 3001; sample()
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(control.state.value.connected)
        meter(); sample()
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.isEmpty())
    }
    @Test fun sourceLossAlsoClearsPausedErgAndRevokesOwnership() {
        sample(); meter()
        control.procedure("ble:a", byteArrayOf(0))
        control.procedure("ble:a", byteArrayOf(5, 200.toByte(), 0))
        control.procedure("ble:a", byteArrayOf(8, 2))
        control.useExternalPower(null)
        assertEquals(1, control.state.value.permissionLostCount)
        assertEquals(5, control.procedure("ble:a", byteArrayOf(7)).result)
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }
    @Test fun externalSourceChangeDoesNotInterruptSimulation() {
        sample(); control.localSimulation(); meter()
        assertEquals(ControlMode.Simulation, control.state.value.mode)
        control.useExternalPower(null)
        assertEquals(ControlMode.Simulation, control.state.value.mode)
    }
}
