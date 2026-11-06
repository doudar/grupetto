package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class FlywheelTemperatureTest {
    private val ambient = (65.0 - 32) / 1.8

    @Test fun coldStartAndZeroPowerRemainAtRoomTemperature() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.observe(0, 0.0)
        assertEquals(65.0, m.advance(1000).fahrenheit, 1e-9)
        assertTrue(m.state.receivingPower)
        assertTrue(m.state.unobservedHistory)
    }

    @Test fun exactSolutionIsIndependentOfStepSizeAndHasNo110FDegreeCap() {
        var fine = ambient
        repeat(1800) { fine = thermalStep(fine, ambient, 200.0, 1.0) }
        assertEquals(thermalStep(ambient, ambient, 200.0, 1800.0), fine, 1e-9)
        assertEquals(102.56155, fine * 1.8 + 32, .0001)
        assertTrue(thermalStep(ambient, ambient, 400.0, 3600.0) * 1.8 + 32 > 110)
        assertEquals(ambient + 25, thermalStep(ambient, ambient, 200.0, 1e8), 1e-9)
    }

    @Test fun stalePowerHeatsOnlyForThreeSecondsThenCools() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.observe(0, 300.0)
        val expected = thermalStep(thermalStep(ambient, ambient, 300.0, 3.0), ambient, 0.0, 57.0)
        assertEquals(expected, m.advance(60_000).celsius, 1e-9)
        assertFalse(m.state.receivingPower)
        assertTrue(m.state.unobservedHistory)
    }

    @Test fun duplicateAndOutOfOrderFramesCannotExtendHeatOrReplacePower() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.observe(0, 200.0)
        m.observe(0, 2000.0)
        m.observe(-1, 2000.0)
        assertEquals(thermalStep(ambient, ambient, 200.0, 1.0), m.advance(1000).celsius, 1e-9)
        val old = m.state
        assertEquals(old, m.advance(500))
    }

    @Test fun invalidInputEndsHeatAndDoesNotPoisonEstimate() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 3001.0).forEach { invalid ->
            val m = FlywheelTemperatureModel(0, 1000)
            m.observe(0, 200.0); m.observe(1000, invalid)
            assertFalse(m.advance(2000).receivingPower)
            assertEquals(thermalStep(thermalStep(ambient, ambient, 200.0, 1.0), ambient, 0.0, 1.0), m.state.celsius, 1e-9)
        }
    }

    @Test fun restartCoolsWithoutReplayingOldPowerAndFlagsUnknownRiding() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.observe(0, 300.0); m.advance(2000)
        val checkpoint = m.checkpoint(3000)
        val restored = FlywheelTemperatureModel(17, 63_000, checkpoint)
        assertEquals(thermalStep(checkpoint.celsius, ambient, 0.0, 60.0), restored.state.celsius, 1e-9)
        assertFalse(restored.state.receivingPower)
        assertTrue(restored.state.unobservedHistory)
        assertEquals(checkpoint.celsius, FlywheelTemperatureModel(0, 1, checkpoint).state.celsius, 0.0)
    }

    @Test fun roomEditPreservesAbsoluteTemperatureAndChangesCoolingDestination() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.setRoomTemperature(80.0, 0)
        assertEquals(65.0, m.state.fahrenheit, 1e-9)
        assertEquals(80.0, m.advance(100_000_000).fahrenheit, 1e-9)
        assertEquals(80.0, FlywheelTemperatureModel(0, 1000, m.checkpoint(1000)).state.roomFahrenheit, 0.0)
    }

    @Test fun invalidOrFutureVersionCheckpointFallsBackSafely() {
        listOf(ThermalCheckpoint(2, 40.0, 65.0, 1), ThermalCheckpoint(1, Double.NaN, 65.0, 1),
            ThermalCheckpoint(1, 40.0, 111.0, 1)).forEach {
            assertEquals(65.0, FlywheelTemperatureModel(0, 1000, it).state.fahrenheit, 1e-9)
        }
        assertFalse(validRoomTemperature(Double.NaN))
        assertFalse(validRoomTemperature(31.0))
        assertTrue(validRoomTemperature(65.0))
    }
}
