package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test

class BikeControlTest {
    private var now = 1000L
    private var resistance = 40
    private val writes = mutableListOf<Int>()
    private var writeSucceeds = true
    private var holdEcho = false
    private val control = BikeControl(true, { now }) {
        writes.add(it)
        if (writeSucceeds && !holdEcho) resistance = it
        writeSucceeds
    }
    private fun sample(power: Float = 100f, cadence: Float = 80f, elapsed: Long = 200) {
        now += elapsed
        control.acceptSample(BikeSample(power, cadence, resistance, now))
        control.tick()
    }
    private fun command(vararg bytes: Int, client: String = "ble:a") =
        control.procedure(client, bytes.map { it.toByte() }.toByteArray())
    private fun acquire() { pedal(100f, 80f); assertEquals(1, command(0).result) }
    /** Steady pedaling past the spin-up window, so modeled jumps are allowed. */
    private fun pedal(power: Float = 168f, cadence: Float = 90f) = repeat(80) { sample(power, cadence) }

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
        pedal(100f, 80f)
        assertTrue(control.localErg(200))
        repeat(10) { sample(100f) }
        // 200 W at 80 RPM models to level 51; a modeled jump ramps at 10 levels/s.
        assertTrue(resistance in 41..52)
        control.localErg(25)
        repeat(30) { sample(200f) }
        assertTrue(resistance < 40)
        assertTrue(writes.all { it in 0..100 })
        // Modeled jumps move at the 10 levels/s ramp speed and trims at 3 levels/s: with one
        // tick per 200 ms sample here, no write is more than 2 levels from the previous one.
        assertTrue(writes.zipWithNext().all { (a, b) -> kotlin.math.abs(a - b) <= 2 })
    }
    @Test fun noMotionBelowMinimumCadenceAndNoWindup() {
        pedal(100f, 80f); control.localErg(1000)
        repeat(100) { sample(0f, 0f) }
        assertTrue(writes.isEmpty())
        sample(0f, 24f)
        assertTrue(writes.isEmpty())
        sample(100f, 80f)
        // No accumulated windup: restarting after a stop adds nothing while the rider spins up.
        assertTrue("held during spin-up, got $writes", writes.isEmpty())
        repeat(80) { sample(100f, 80f) }
        // Then one ramp to the modeled level, capped at FEED_FORWARD_MAX_LEVEL, at the ramp speed.
        assertTrue(writes.isNotEmpty() && writes.all { it <= BikeControl.FEED_FORWARD_MAX_LEVEL })
        assertTrue(writes.zipWithNext().all { (a, b) -> b - a in 0..2 })
    }
    @Test fun powerDeadbandDoesNotHunt() {
        sample(150f); control.localErg(150)
        repeat(50) { sample(152f) }
        assertTrue(writes.isEmpty())
    }
    @Test fun staleTelemetryStopsAndCannotResumeAutomatically() {
        pedal(100f, 80f); control.localErg(250)
        now += 1501
        control.tick()
        assertFalse(control.state.value.connected)
        sample()
        repeat(10) { sample() }
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.isEmpty())
    }
    @Test fun repeatedTelemetryCannotDriveMotor() {
        pedal(100f, 80f); control.localErg(250)
        repeat(10) { now += 100; control.tick() }
        assertTrue(writes.isEmpty())
    }
    @Test fun invalidSampleDisarms() {
        pedal(100f, 80f); control.localErg(250)
        control.acceptSample(BikeSample(Float.NaN, 80f, 40, now))
        assertFalse(control.state.value.connected)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.isEmpty())
    }
    @Test fun binderFailureStopsControl() {
        pedal(100f, 80f); control.localErg(250)
        writeSucceeds = false
        repeat(10) { sample() }
        assertEquals(1, writes.size)
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertEquals("Resistance command failed", control.state.value.message)
    }
    @Test fun knobOverrideIsNotFought() {
        pedal(100f, 80f); control.localErg(250)
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
        pedal(100f, 80f); control.localErg(200)
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

    @Test fun largeErgStepJumpsToModeledLevelWithoutWindup() {
        resistance = 44
        pedal()
        assertTrue(control.localErg(220))
        // The 10 levels/s ramp reaches the modeled level within three samples.
        repeat(3) { sample(168f, 90f) }
        assertEquals(49, resistance)
        // Held there through the 4.5 s settle even though the lagging power report still reads
        // 52 W low: the controller must not keep climbing past the modeled level.
        repeat(22) { sample(168f, 90f) }
        assertEquals(49, resistance)
        assertTrue(writes.all { it in 45..49 })
        assertTrue(writes.zipWithNext().all { (a, b) -> b - a in 1..2 })
    }
    @Test fun rampMovesOneLevelPerTickBetweenSamples() {
        resistance = 44
        pedal()
        assertTrue(control.localErg(220))
        sample(168f, 90f)
        assertEquals(listOf(45), writes)
        // The 100 ms control loop keeps the ramp moving without waiting for new telemetry.
        now += 100; control.tick()
        assertEquals(listOf(45, 46), writes)
        now += 100; control.tick()
        assertEquals(listOf(45, 46, 47), writes)
    }
    @Test fun rampSpeedIsAdjustableAndClamped() {
        assertEquals(BikeControl.RAMP_RATE_DEFAULT, control.state.value.rampRate)
        control.tune(2, .007f, rampRate = 15)
        assertEquals(15, control.state.value.rampRate)
        control.tune(2, .007f, rampRate = 100)
        assertEquals(BikeControl.RAMP_RATE_MAX, control.state.value.rampRate)
        control.tune(2, .007f, rampRate = 0)
        assertEquals(BikeControl.RAMP_RATE_MIN, control.state.value.rampRate)
        // Other tuning keeps the chosen ramp speed.
        control.tune(2, .007f, rampRate = 12); control.tune(3, .01f)
        assertEquals(12, control.state.value.rampRate)
    }
    @Test fun slowerRampMovesHalfALevelPerTick() {
        resistance = 44
        control.tune(2, .007f, rampRate = 5)
        pedal()
        assertTrue(control.localErg(220))
        sample(168f, 90f)
        assertEquals(listOf(45), writes)
        repeat(4) { now += 100; control.tick() }
        // 5 levels/s: two more levels in 400 ms (10 levels/s would have written four).
        assertEquals(listOf(45, 46, 47), writes)
    }
    @Test fun fasterRampReachesTheModeledLevelSooner() {
        resistance = 44
        control.tune(2, .007f, rampRate = 20)
        pedal()
        assertTrue(control.localErg(220))
        sample(168f, 90f)
        now += 100; control.tick()
        assertTrue("20 levels/s moves two levels per tick, got $writes", writes.last() >= 47)
    }
    @Test fun knobMovedDuringRampStopsWithoutFighting() {
        resistance = 44
        pedal()
        assertTrue(control.localErg(345))
        holdEcho = true
        sample(168f, 90f)
        // The echo may trail the ramp by up to 5 levels before the ramp pauses.
        repeat(2) { sample(168f, 90f) }
        val written = writes.size
        resistance = 30
        repeat(10) { sample(168f, 90f) }
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(writes.size <= written + 1)
    }
    @Test fun dropUsesCadenceHeldAtTheLowerTarget() {
        resistance = 43
        sample(170f, 90f)
        assertTrue(control.localErg(170))
        repeat(150) { sample(170f, 90f) }           // 30 s settled at 170 W, 90 rpm
        assertTrue(control.localErg(360))
        repeat(150) { sample(360f, 97f) }           // a hard effort spun at 97 rpm
        val expected = control.powerModel.nearestLevelFor(170f, 90f)!!
        val atHighCadence = control.powerModel.nearestLevelFor(170f, 97f)!!
        assertTrue(expected != atHighCadence)
        assertTrue(control.localErg(170))
        repeat(15) { sample(360f, 97f) }            // the ramp runs while cadence is still high
        assertEquals(expected, resistance)
    }
    @Test fun deadbandWidensWithResistanceLevelSize() {
        // Near 345 W one level is about 13 W, so a 5 W shortfall is held.
        resistance = 58
        repeat(3) { sample(340f, 90f) }
        assertTrue(control.localErg(345))
        repeat(50) { sample(340f, 90f) }
        assertTrue(writes.isEmpty())
    }
    @Test fun deadbandStaysNarrowAtModeratePower() {
        // Near 170 W one level is about 10 W, so a 5 W shortfall is still trimmed.
        resistance = 44
        repeat(3) { sample(170f, 90f) }
        assertTrue(control.localErg(175))
        repeat(50) { sample(170f, 90f) }
        assertTrue(writes.isNotEmpty())
    }
    @Test fun moreResistanceWaitsUntilSpinUpIsOver() {
        // Oct 31: ERG 165 W arrived 5 s after the rider started pedaling; a jump sized for the
        // 55 rpm average put out ~285 W at 75 rpm. Now the level holds while the rider spins up,
        // then jumps once, sized for the cadence they actually settled at.
        resistance = 30
        repeat(10) { sample(0f, 0f) }
        repeat(20) { sample(40f, 60f) }   // 4 s of pedaling
        assertTrue(control.localErg(165))
        repeat(50) { sample(70f, 80f) }   // to 14 s: still spinning up
        assertTrue("held while spinning up, got $writes", writes.isEmpty())
        repeat(20) { sample(70f, 80f) }   // past 15 s, ramp done
        // One jump, to about the level 165 W needs at the 80 rpm the rider settled at.
        val expected = control.powerModel.nearestLevelFor(165f, 80f)!!
        assertTrue("expected about $expected, got $resistance", resistance in expected - 1..expected + 1)
        assertTrue(writes.zipWithNext().all { (x, y) -> y - x in 1..2 })
    }
    @Test fun jumpsNormallyAfterSpinUp() {
        resistance = 30
        repeat(10) { sample(0f, 0f) }
        repeat(80) { sample(60f, 80f) }   // 16 s of pedaling
        assertTrue(control.localErg(165))
        repeat(5) { sample(60f, 80f) }    // 1 s
        assertTrue("expected a modeled jump, got $writes", resistance >= 38)
    }
    @Test fun stoppingRestartsTheSpinUpWindow() {
        resistance = 30
        pedal(60f, 80f)
        repeat(10) { sample(0f, 0f) }     // stopped
        repeat(10) { sample(40f, 60f) }   // pedaling again for 2 s
        assertTrue(control.localErg(165))
        repeat(5) { sample(60f, 75f) }
        assertTrue("held while spinning up, got $writes", writes.isEmpty())
    }
    @Test fun spinUpNeverAddsResistanceForALowerTarget() {
        resistance = 60
        repeat(10) { sample(0f, 0f) }
        repeat(10) { sample(200f, 60f) }  // 2 s of heavy pedaling at a low cadence
        assertTrue(control.localErg(150))
        repeat(10) { sample(190f, 60f) }
        assertTrue("got $writes", writes.all { it <= 60 } && resistance <= 60)
    }
    @Test fun smallErgStepKeepsProportionalTrim() {
        resistance = 44
        sample(168f, 90f)
        assertTrue(control.localErg(175))
        sample(168f, 90f)
        assertTrue(resistance in 44..45)
    }
    @Test fun powerModelLearnsSteadyReadingsInAnyMode() {
        resistance = 44
        repeat(30) { sample(175f, 90f) }
        assertEquals(175f, control.powerModel.wattsAt90(44), 1f)
        assertEquals(44f, control.powerModel.levelFor(175f, 90f)!!, .2f)
        // Neighbouring unlearned levels are scaled by the learned correction.
        assertTrue(control.powerModel.wattsAt90(45) > PowerModel.seedWatts(45))
        assertTrue(writes.isEmpty())
    }
}
