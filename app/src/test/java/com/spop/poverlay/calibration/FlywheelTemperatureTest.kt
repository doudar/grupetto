package com.spop.poverlay.calibration

import kotlin.math.exp
import org.junit.Assert.*
import org.junit.Test

class FlywheelTemperatureTest {
    private val ambient = (72.0 - 32) / 1.8
    private fun nodes(state: FlywheelTemperatureState) = FlywheelNodes(state.celsius, state.hubCelsius)
    private fun uniform(t: Double = ambient) = FlywheelNodes(t, t)
    private fun assertNodes(expected: FlywheelNodes, actual: FlywheelNodes, tolerance: Double = 1e-9) {
        assertEquals(expected.discCelsius, actual.discCelsius, tolerance)
        assertEquals(expected.hubCelsius, actual.hubCelsius, tolerance)
    }

    @Test fun coldStartAndNoTelemetryRemainAtRoomTemperature() {
        val m = FlywheelTemperatureModel(0, 1000)
        assertNodes(uniform(), nodes(m.advance(1_000_000)))
        assertFalse(m.state.receivingPower)
        m.observe(1_000_000, 0.0)
        assertEquals(72.0, m.advance(1_001_000).fahrenheit, 1e-9)
        assertTrue(m.state.receivingPower)
    }

    @Test fun exactSolutionIsIndependentOfStepSizeAndHasNo110FDegreeCap() {
        listOf(uniform(), FlywheelNodes(65.0, 30.0), FlywheelNodes(25.0, 60.0)).forEach { initial ->
            var fine = initial
            repeat(1800) { fine = thermalStep(fine, ambient, 200.0, 1.0) }
            assertNodes(thermalStep(initial, ambient, 200.0, 1800.0), fine)
        }
        assertTrue(thermalStep(uniform(), ambient, 400.0, 3600.0).discCelsius * 1.8 + 32 > 110)
        assertNodes(FlywheelNodes(ambient + 30, ambient + 20),
            thermalStep(uniform(), ambient, 200.0, 1e8))
        assertNodes(uniform(), thermalStep(FlywheelNodes(70.0, 40.0), ambient, 0.0, 1e15))
    }

    @Test fun exactStepMatchesIndependentRungeKuttaAndEnergyBalance() {
        fun derivative(s: FlywheelNodes, watts: Double) = FlywheelNodes(
            (watts - 4 * (s.discCelsius - ambient) - 8 * (s.discCelsius - s.hubCelsius)) / 2000,
            (8 * (s.discCelsius - s.hubCelsius) - 4 * (s.hubCelsius - ambient)) / 6000)
        fun add(s: FlywheelNodes, d: FlywheelNodes, scale: Double) =
            FlywheelNodes(s.discCelsius + d.discCelsius * scale, s.hubCelsius + d.hubCelsius * scale)
        listOf(0.0, 200.0, 3000.0).forEach { watts ->
            val start = FlywheelNodes(55.0, 35.0)
            var numerical = start
            repeat(6000) {
                val k1 = derivative(numerical, watts)
                val k2 = derivative(add(numerical, k1, .05), watts)
                val k3 = derivative(add(numerical, k2, .05), watts)
                val k4 = derivative(add(numerical, k3, .1), watts)
                numerical = add(numerical, FlywheelNodes(
                    (k1.discCelsius + 2*k2.discCelsius + 2*k3.discCelsius + k4.discCelsius)/6,
                    (k1.hubCelsius + 2*k2.hubCelsius + 2*k3.hubCelsius + k4.hubCelsius)/6), .1)
            }
            assertNodes(numerical, thermalStep(start, ambient, watts, 600.0), 1e-8)
            val dt = .0001
            val next = thermalStep(start, ambient, watts, dt)
            val storedWatts = (2000*(next.discCelsius-start.discCelsius) +
                6000*(next.hubCelsius-start.hubCelsius))/dt
            assertEquals(watts-4*(55-ambient)-4*(35-ambient), storedWatts, .002)
        }
    }

    @Test fun discRespondsFasterThanSingleNodeBaselinesWithoutDiscardingHubHeat() {
        fun baseline(capacity: Double, watts: Double, seconds: Double, start: Double = ambient) =
            ambient + watts/8 + (start-ambient-watts/8)*exp(-8*seconds/capacity)
        val heated = thermalStep(uniform(), ambient, 200.0, 60.0)
        assertTrue(heated.discCelsius > baseline(6400.0, 200.0, 60.0))
        assertTrue(heated.discCelsius > baseline(8000.0, 200.0, 60.0))
        assertTrue(heated.hubCelsius < heated.discCelsius)
        // An illustrative hot rim/cooler hub has fast initial cooling and a slower tail.
        val start = FlywheelNodes(60.0, 40.0)
        val early = thermalStep(start, ambient, 0.0, 120.0)
        val late = thermalStep(start, ambient, 0.0, 1200.0)
        val later = thermalStep(late, ambient, 0.0, 120.0)
        assertTrue(start.discCelsius-early.discCelsius > late.discCelsius-later.discCelsius)
        assertTrue(early.discCelsius < baseline(6400.0, 0.0, 120.0, start.discCelsius))
        assertTrue(early.hubCelsius > start.hubCelsius)
        // A hotter hub can legitimately rewarm the disc with no incoming watts.
        val rewarming = thermalStep(FlywheelNodes(30.0, 70.0), ambient, 0.0, 1.0)
        assertTrue(rewarming.discCelsius > 30.0)
        assertTrue(rewarming.hubCelsius < 70.0)
    }

    @Test fun stalePowerHeatsOnlyForThreeSecondsThenCoolsBothNodes() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.observe(0, 300.0)
        val expected = thermalStep(thermalStep(uniform(), ambient, 300.0, 3.0), ambient, 0.0, 57.0)
        assertNodes(expected, nodes(m.advance(60_000)))
        assertFalse(m.state.receivingPower)
        assertNodes(thermalStep(expected, ambient, 0.0, 60.0), nodes(m.advance(120_000)))
    }

    @Test fun duplicateAndOutOfOrderFramesCannotExtendHeatOrReplacePower() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.observe(0, 200.0)
        m.observe(0, 2000.0)
        m.observe(-1, 2000.0)
        assertNodes(thermalStep(uniform(), ambient, 200.0, 1.0), nodes(m.advance(1000)))
        val old = m.state
        assertEquals(old, m.advance(500))
    }

    @Test fun invalidInputEndsHeatAndDoesNotPoisonEitherNode() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 3001.0).forEach { invalid ->
            val m = FlywheelTemperatureModel(0, 1000)
            m.observe(0, 200.0); m.observe(1000, invalid)
            assertFalse(m.advance(2000).receivingPower)
            assertNodes(thermalStep(thermalStep(uniform(), ambient, 200.0, 1.0), ambient, 0.0, 1.0), nodes(m.state))
        }
    }

    @Test fun restartPreservesGradientAndCoolsWithoutReplayingOldPower() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.observe(0, 300.0); m.advance(2000)
        val checkpoint = m.checkpoint(3000)
        assertEquals(2, checkpoint.version)
        assertNotEquals(checkpoint.celsius, checkpoint.hubCelsius!!, 1e-6)
        val restored = FlywheelTemperatureModel(17, 63_000, checkpoint)
        assertNodes(thermalStep(nodes(m.state), ambient, 0.0, 60.0), nodes(restored.state))
        assertFalse(restored.state.receivingPower)
        assertNodes(nodes(m.state), nodes(FlywheelTemperatureModel(0, 1, checkpoint).state), 0.0)
    }

    @Test fun versionOneMigratesWithoutInventingGradientOrRewritingBaseline() {
        val old = ThermalCheckpoint(1, 40.0, 65.0, 1000)
        val restored = FlywheelTemperatureModel(0, 1000, old)
        assertNodes(uniform(40.0), nodes(restored.state), 0.0)
        assertEquals(65.0, restored.state.roomFahrenheit, 0.0)
        assertNodes(thermalStep(uniform(40.0), (65.0-32)/1.8, 0.0, 60.0),
            nodes(FlywheelTemperatureModel(0, 61_000, old).state))
        assertEquals(2, restored.checkpoint(1000).version)
    }

    @Test fun coldRoomEditImmediatelyTracksBaselineAndPersistsIt() {
        val m = FlywheelTemperatureModel(0, 1000)
        m.setRoomTemperature(80.0, 0)
        assertNodes(uniform((80.0-32)/1.8), nodes(m.state))
        assertEquals(80.0, m.advance(100_000_000).fahrenheit, 1e-9)
        assertEquals(80.0, FlywheelTemperatureModel(0, 1000, m.checkpoint(1000)).state.roomFahrenheit, 0.0)
    }

    @Test fun warmRoomEditPreservesBothRisesAndGradientAndAdvancesOldBaselineFirst() {
        val oldAmbient = (65.0 - 32) / 1.8
        val m = FlywheelTemperatureModel(0, 1000, ThermalCheckpoint(2, 50.0, 65.0, 1000, 30.0))
        m.observe(0, 200.0)
        val expected = thermalStep(FlywheelNodes(50.0, 30.0), oldAmbient, 200.0, 1.0)
        m.setRoomTemperature(72.0, 1000)
        assertNodes(FlywheelNodes(expected.discCelsius+7/1.8, expected.hubCelsius+7/1.8), nodes(m.state))
        val shifted = m.state
        m.setRoomTemperature(72.0, 1000)
        assertEquals(shifted, m.state)
        m.setRoomTemperature(60.0, 1000)
        assertNodes(FlywheelNodes(expected.discCelsius-5/1.8, expected.hubCelsius-5/1.8), nodes(m.state))
        val before = nodes(m.state)
        assertNodes(thermalStep(before, (60.0-32)/1.8, 200.0, 1.0), nodes(m.advance(2000)))
    }

    @Test fun roomEditPreservesNegativeOffsetAndRejectsInvalidWithoutMutation() {
        val m = FlywheelTemperatureModel(0, 1000, ThermalCheckpoint(1, (60.0-32)/1.8,65.0,1000))
        m.setRoomTemperature(75.0,0)
        assertEquals(70.0,m.state.fahrenheit,1e-9)
        val before = m.state
        listOf(Double.NaN, Double.POSITIVE_INFINITY,31.0,111.0).forEach {
            assertThrows(IllegalArgumentException::class.java) { m.setRoomTemperature(it,1000) }
            assertEquals(before,m.state)
        }
    }

    @Test fun savedRoomAndThermalReferenceAreUnchanged() {
        listOf(65.0, 70.0, 80.0).forEach { room ->
            val saved = ThermalCheckpoint(1, (90.0 - 32) / 1.8, room, 1000)
            val restored = FlywheelTemperatureModel(0, 1000, saved)
            assertEquals(room, restored.state.roomFahrenheit, 0.0)
            assertEquals(90.0, restored.state.fahrenheit, 1e-9)
        }
        assertEquals((65.0 - 32) / 1.8, CalibrationThermalCorrection.ReferenceCelsius, 0.0)
    }

    @Test fun invalidOrFutureVersionCheckpointFallsBackSafely() {
        listOf(ThermalCheckpoint(3, 40.0, 65.0, 1), ThermalCheckpoint(1, Double.NaN, 65.0, 1),
            ThermalCheckpoint(1, 40.0, 111.0, 1), ThermalCheckpoint(2, 40.0, 65.0, 1),
            ThermalCheckpoint(2, 40.0, 65.0, 1, Double.NaN), ThermalCheckpoint(2, 40.0, 65.0, 1, 501.0)).forEach {
            assertEquals(72.0, FlywheelTemperatureModel(0, 1000, it).state.fahrenheit, 1e-9)
        }
        assertFalse(validRoomTemperature(Double.NaN))
        assertFalse(validRoomTemperature(31.0))
        assertTrue(validRoomTemperature(65.0))
    }
}
