package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test

class BikeControlTest {
    private var now = 1000L
    private var resistance = 40
    private val writes = mutableListOf<Int>()
    private var writeSucceeds = true
    private val control = BikeControl(true, { now }) {
        writes.add(it)
        if (writeSucceeds) resistance = it
        writeSucceeds
    }
    private fun sample(power: Float = 100f, cadence: Float = 80f, elapsed: Long = 200) {
        now += elapsed
        control.acceptSample(BikeSample(power, cadence, resistance, now))
        control.tick()
    }
    private fun command(vararg bytes: Int, client: String = "ble:a") =
        control.procedure(client, bytes.map { it.toByte() }.toByteArray())
    private fun acquire() { sample(); assertEquals(1, command(0).result) }

    @Test fun requiresFreshConnectedHardware() {
        assertEquals(4, command(0).result)
        assertFalse(control.localErg(150))
        sample()
        assertTrue(control.state.value.connected)
        now += 1501
        control.tick()
        assertFalse(control.state.value.connected)
        assertEquals(4, command(0).result)
    }
    @Test fun unsupportedHardwareNeverWrites() {
        val disabled = BikeControl(false, { now }) { fail("Must not write"); true }
        disabled.acceptSample(BikeSample(100f, 80f, 40, now))
        assertFalse(disabled.localErg(200))
        assertFalse(disabled.localSimulation())
        assertEquals(2, disabled.procedure("a", byteArrayOf(0)).result)
        disabled.tick()
        assertFalse(disabled.state.value.connected)
    }
    @Test fun oneOwnerAcrossBothTransports() {
        acquire()
        assertEquals(5, command(0, client = "dircon:b").result)
        assertEquals(5, command(5, 200, 0, client = "dircon:b").result)
        assertFalse(control.localErg(200))
        assertEquals(1, command(5, 200, 0).result)
        control.disconnect("dircon:b")
        assertEquals(ControlMode.Erg, control.state.value.mode)
        control.disconnect("ble:a")
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertEquals(1, command(0, client = "dircon:b").result)
    }
    @Test fun onlyOwningTransportStopDisarms() {
        acquire(); command(5, 200, 0)
        control.disconnectTransport("dircon:")
        assertEquals(ControlMode.Erg, control.state.value.mode)
        control.disconnectTransport("ble:")
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }
    @Test fun validatesAllLengthsBeforeActing() {
        acquire()
        listOf(intArrayOf(), intArrayOf(0, 1), intArrayOf(1, 1), intArrayOf(4),
            intArrayOf(4, 40), intArrayOf(4, 40, 0, 0), intArrayOf(5, 100), intArrayOf(5, 100, 0, 0),
            intArrayOf(7, 0), intArrayOf(8), intArrayOf(0x11, 0, 0, 0, 0, 0),
            intArrayOf(0x11, 0, 0, 0, 0, 0, 0, 0)).forEach {
            assertEquals(it.contentToString(), 3, command(*it).result)
        }
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.isEmpty())
    }
    @Test fun signedPowerAndAdvertisedLimits() {
        acquire()
        for (watts in listOf(-1, 0, 24, 1001, 32767)) {
            assertEquals(3, command(5, watts and 255, watts shr 8).result)
        }
        for (watts in listOf(25, 150, 1000)) {
            val reply = command(5, watts and 255, watts shr 8)
            assertEquals(1, reply.result)
            assertEquals(watts, control.state.value.targetWatts)
            assertArrayEquals(byteArrayOf(8, watts.toByte(), (watts shr 8).toByte()), reply.status)
        }
    }
    @Test fun unsupportedOpcodeDoesNotChangeMode() {
        acquire(); command(5, 150, 0)
        assertEquals(2, command(0x12, 0, 0).result)
        assertEquals(ControlMode.Erg, control.state.value.mode)
    }
    @Test fun resistanceUsesCorrectedSigned16BitTenthsAndEnforcesRange() {
        acquire()
        assertEquals(1, command(4, 232, 3).result)
        assertEquals(ControlMode.Resistance, control.state.value.mode)
        assertEquals(3, command(4, 233, 3).result)
        assertEquals(3, command(4, 255, 255).result)
        assertEquals(3, command(4, 1, 0).result)
        repeat(20) { sample() }
        assertTrue(resistance > 40)
        assertTrue(resistance <= 52)
    }
    @Test fun resetAndStopDisarmWithoutMotorJump() {
        acquire(); command(5, 250, 0)
        assertEquals(3, command(8, 3).result)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(1, command(8, 1).result)
        repeat(5) { sample() }
        assertTrue(writes.isEmpty())
        assertEquals(1, command(7).result)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        command(5, 200, 0)
        assertEquals(1, command(1).result)
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }
    @Test fun ergIncreasesAndDecreasesWithRateLimit() {
        sample()
        assertTrue(control.localErg(200))
        repeat(10) { sample(100f) }
        assertTrue(resistance in 41..46)
        control.localErg(25)
        repeat(30) { sample(200f) }
        assertTrue(resistance < 40)
        assertTrue(writes.all { it in 0..100 })
        assertTrue(writes.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) <= 1 })
    }
    @Test fun noMotionBelowMinimumCadenceAndNoWindup() {
        sample(); control.localErg(1000)
        repeat(100) { sample(0f, 0f) }
        assertTrue(writes.isEmpty())
        sample(0f, 24f)
        assertTrue(writes.isEmpty())
        sample(100f, 80f)
        assertTrue(resistance <= 41)
    }
    @Test fun powerDeadbandDoesNotHunt() {
        sample(150f); control.localErg(150)
        repeat(50) { sample(152f) }
        assertTrue(writes.isEmpty())
    }
    @Test fun staleTelemetryStopsAndCannotResumeAutomatically() {
        sample(); control.localErg(250)
        now += 1501
        control.tick()
        assertFalse(control.state.value.connected)
        sample()
        repeat(10) { sample() }
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.isEmpty())
    }
    @Test fun repeatedTelemetryCannotDriveMotor() {
        sample(); control.localErg(250)
        repeat(10) { now += 100; control.tick() }
        assertTrue(writes.isEmpty())
    }
    @Test fun invalidSampleDisarms() {
        sample(); control.localErg(250)
        control.acceptSample(BikeSample(Float.NaN, 80f, 40, now))
        assertFalse(control.state.value.connected)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.isEmpty())
    }
    @Test fun binderFailureStopsControl() {
        sample(); control.localErg(250)
        writeSucceeds = false
        repeat(10) { sample() }
        assertEquals(1, writes.size)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertEquals("Resistance command failed", control.state.value.message)
    }
    @Test fun knobOverrideIsNotFought() {
        sample(); control.localErg(250)
        resistance = 20
        repeat(10) { sample() }
        assertTrue(writes.isEmpty())
        assertEquals(ControlMode.Manual, control.state.value.mode)
    }
    @Test fun simulationShiftsPersistAcrossGradePackets() {
        acquire()
        assertEquals(1, command(0x11, 0, 0, 0, 0, 40, 51).result)
        control.shift(1)
        assertEquals(2, control.state.value.shiftOffset)
        command(0x11, 0, 0, 244, 1, 40, 51) // +5%
        assertEquals(2, control.state.value.shiftOffset)
        repeat(30) { sample() }
        assertTrue(resistance > 42)
        control.shift(-1)
        assertEquals(0, control.state.value.shiftOffset)
        command(5, 200, 0)
        control.shift(1)
        assertEquals(0, control.state.value.shiftOffset)
    }
    @Test fun downhillSimulationUsesSignedGrade() {
        acquire()
        command(0x11, 0, 0, 12, 254, 40, 51) // -5%
        repeat(20) { sample() }
        assertTrue(resistance < 40)
    }
    @Test fun simulationPhysicsIncludesWindAndRollingResistance() {
        val flat = Simulation(0f, 0f, .004f, .51f).resistanceOffset()
        assertEquals(0f, flat, .001f)
        assertEquals(10f, Simulation(0f, 5f, .004f, .51f).resistanceOffset(), .001f)
        assertTrue(Simulation(5f, 0f, .004f, .51f).resistanceOffset() > flat)
        assertTrue(Simulation(-5f, 0f, .004f, .51f).resistanceOffset() < flat)
        assertTrue(Simulation(0f, 0f, .01f, .51f).resistanceOffset() > flat)
    }
    @Test fun tuningClampedAndAppliedToShifts() {
        control.tune(100, Float.NaN)
        assertEquals(10, control.state.value.shiftSize)
        assertEquals(.007f, control.state.value.gain, 0f)
        control.tune(-10, 100f)
        assertEquals(1, control.state.value.shiftSize)
        assertEquals(.03f, control.state.value.gain, 0f)
        sample(); control.localSimulation()
        control.shift(1)
        assertEquals(1, control.state.value.shiftOffset)
    }
    @Test fun shiftSaturationDoesNotAccumulateHiddenShifts() {
        sample(); control.localSimulation()
        repeat(100) { control.shift(1) }
        assertEquals(60, control.state.value.shiftOffset)
        control.shift(-1)
        assertEquals(58, control.state.value.shiftOffset)
        repeat(100) { control.shift(-1) }
        assertEquals(-40, control.state.value.shiftOffset)
    }
    @Test fun disconnectedSampleStopsImmediately() {
        sample(); control.localErg(200)
        control.acceptSample(null)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertFalse(control.state.value.connected)
    }
    @Test fun pauseAndResumePreserveTargetAndOwnership() {
        acquire(); command(5, 200, 0)
        val revision = control.state.value.permissionLostCount
        assertEquals(1, command(8, 2).result)
        repeat(10) { sample() }
        assertTrue(writes.isEmpty())
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertEquals(revision, control.state.value.permissionLostCount)
        assertEquals(1, command(7).result)
        assertEquals(ControlMode.Erg, control.state.value.mode)
        assertEquals(200, control.state.value.targetWatts)
        repeat(10) { sample() }
        assertTrue(writes.isNotEmpty())
    }
    @Test fun manualTakeoverRevokesRemotePermission() {
        acquire(); command(5, 200, 0)
        control.stop()
        assertEquals(1, control.state.value.permissionLostCount)
        assertEquals(5, command(5, 200, 0).result)
        assertTrue(control.localSimulation())
    }
}
