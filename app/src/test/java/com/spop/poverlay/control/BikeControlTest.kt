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
        assertTrue(control.localErg(200))
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

    @Test fun remoteCommandsOverrideAllLocalModesAndTargetsOnBothTransports() {
        sample()
        for (client in listOf("ble:a", "dircon:b")) {
            control.localErg(150)
            assertEquals(1, command(0, client = client).result) // Local mode does not block acquisition.
            control.localManual()
            assertEquals(1, command(5, 210, 0, client = client).result)
            assertEquals(ControlMode.Erg, control.state.value.mode)
            assertEquals(210, control.state.value.targetWatts)
            control.localResistance(30)
            assertEquals(1, command(0x11, 0, 0, 12, 254, 40, 51, client = client).result)
            assertEquals(ControlMode.Simulation, control.state.value.mode)
            assertEquals(-5f, control.state.value.targetIncline, .001f)
            control.localErg(180)
            assertEquals(1, command(4, 38, 2, client = client).result) // 550 tenths = 55.
            assertEquals(ControlMode.Resistance, control.state.value.mode)
            assertEquals(55, control.state.value.targetResistance)
            control.disconnect(client)
        }
    }

    @Test fun ergShiftsUseWattsAndClampWithoutAccumulating() {
        sample(); control.tune(3, .007f, 20, 2f); control.localErg(150)
        control.shift(1)
        assertEquals(170, control.state.value.targetWatts)
        assertTrue(writes.isEmpty()) // A shift changes the target, not the motor directly.
        repeat(100) { control.shift(1) }
        assertEquals(1000, control.state.value.targetWatts)
        control.shift(-1)
        assertEquals(980, control.state.value.targetWatts)
        repeat(100) { control.shift(-1) }
        assertEquals(25, control.state.value.targetWatts)
        control.shift(1)
        assertEquals(45, control.state.value.targetWatts)
    }

    @Test fun manualShiftsAccumulateAgainstTargetAndRespectRateAndCadenceLimits() {
        sample(); control.tune(3, .007f); control.shift(1); control.shift(1)
        assertEquals(ControlMode.Resistance, control.state.value.mode)
        assertEquals(46, control.state.value.targetResistance)
        repeat(10) { sample(cadence = 0f) }
        assertTrue(writes.isEmpty())
        sample()
        assertEquals(41, resistance)
        repeat(100) { control.shift(1) }
        assertEquals(100, control.state.value.targetResistance)
        control.shift(-1)
        assertEquals(97, control.state.value.targetResistance)
        repeat(100) { control.shift(-1) }
        assertEquals(0, control.state.value.targetResistance)
    }

    @Test fun inclineSensitivityScalesGradeAndTuningPersistsAcrossModeChanges() {
        sample(); control.tune(4, .01f, 15, 1f); control.localSimulation(5f)
        repeat(20) { sample() }
        assertEquals(45, resistance)
        control.tune(4, .01f, 15, 3f)
        repeat(40) { sample() }
        assertEquals(55, resistance)
        control.localErg(150); control.localManual()
        assertEquals(15, control.state.value.wattsPerShift)
        assertEquals(3f, control.state.value.inclineSensitivity, 0f)
        assertEquals(55, control.state.value.targetResistance)
    }

    @Test fun externalFlagRequiresAcceptedTargetAndClearsOnLocalOrSafetyEvents() {
        acquire()
        assertNull(control.state.value.externalControl)
        command(5, 200, 0)
        assertEquals("Bluetooth", control.state.value.externalControl)
        val before = control.state.value
        command(5, 0, 0)
        command(5, 230, 0, client = "dircon:b")
        assertEquals(before, control.state.value)
        control.shift(1)
        assertNull(control.state.value.externalControl)
        command(5, 220, 0)
        assertEquals("Bluetooth", control.state.value.externalControl)
        now += 1501; control.tick()
        assertNull(control.state.value.externalControl)
        sample(); command(0, client = "dircon:b"); command(5, 240, 0, client = "dircon:b")
        assertEquals("DirCon", control.state.value.externalControl)
        control.disconnect("dircon:b")
        assertNull(control.state.value.externalControl)
    }

    @Test fun pauseResumeRetainsResistanceTargetDespiteLiveTelemetry() {
        acquire(); command(4, 88, 2) // 60 resistance.
        command(8, 2)
        repeat(5) { sample() }
        assertNull(control.state.value.externalControl)
        assertEquals(40, control.state.value.targetResistance)
        command(7)
        assertEquals(ControlMode.Resistance, control.state.value.mode)
        assertEquals(60, control.state.value.targetResistance)
        assertEquals("Bluetooth", control.state.value.externalControl)
    }

    @Test fun newTuningAndLocalTargetsValidateInputs() {
        control.tune(2, .007f, 100, Float.NaN)
        assertEquals(50, control.state.value.wattsPerShift)
        assertEquals(2f, control.state.value.inclineSensitivity, 0f)
        control.tune(2, .007f, -1, 100f)
        assertEquals(1, control.state.value.wattsPerShift)
        assertEquals(5f, control.state.value.inclineSensitivity, 0f)
        assertFalse(control.localManual())
        assertFalse(control.localResistance(40))
        sample()
        val before = control.state.value
        assertFalse(control.localResistance(101))
        assertFalse(control.localResistance(-1))
        assertFalse(control.localSimulation(Float.NaN))
        assertFalse(control.localSimulation(Float.POSITIVE_INFINITY))
        assertEquals(before, control.state.value)
    }
}
