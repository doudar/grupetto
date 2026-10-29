package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationWindowTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val window = CalibrationWindow(baseline)
    private fun add(second: Int, ad: Int = 2000, rpm: Double = 80.0, pedal: Int = 190,
                    meter: String = "pedals", pedalAt: Long = second * 1000L, source: Int = 1): CalibrationSample? {
        val now = second * 1000L
        return window.add(CalibrationFrame(now, ad, rpm, 200.0, "bike", source), pedal, pedalAt, meter, now, now)
    }
    @Test fun onlyIndependentSteadyWindowsAreSaved() {
        (0..6).forEach { assertNull(add(it)) }
        val sample = add(7)!!
        assertEquals(190.0, sample.pedalWatts, 0.01)
        assertEquals(2000.0, sample.ad, 0.01)
        (8..14).forEach { assertNull(add(it)) }
        assertNotNull(add(15))
    }
    @Test fun changingResistanceRestartsSettling() {
        (0..5).forEach { assertNull(add(it)) }
        (6..12).forEach { assertNull(add(it, ad = 2100)) }
        assertNotNull(add(13, ad = 2100))
    }
    @Test fun staleOrRepeatedPedalPacketsCannotBecomeTrainingSamples() {
        (0..25).forEach { assertNull(add(it, pedalAt = 0)) }
    }
    @Test fun pedalDisconnectAndMeterChangeRestartTheWindow() {
        (0..5).forEach { add(it) }
        assertNull(add(6, pedalAt = 0))
        (7..10).forEach { assertNull(add(it)) }
        (11..17).forEach { assertNull(add(it, meter = "different")) }
        assertNotNull(add(18, meter = "different"))
    }
    @Test fun tabletOverridePowerAndLowCadenceAreNeverUsed() {
        (0..20).forEach { assertNull(add(it, source = 2)) }
        (21..40).forEach { assertNull(add(it, rpm = 20.0)) }
    }
    @Test fun startupLagIsDiscardedButUnstableSettledPowerIsRejected() {
        (0..6).forEach { assertNull(add(it, pedal = if (it < 3) 100 else 190)) }
        assertEquals(190.0, add(7)!!.pedalWatts, 0.01)
        (8..14).forEach { assertNull(add(it, pedal = if (it % 2 == 0) 100 else 200)) }
        assertNull(add(15))
    }
    @Test fun humanCadenceVariationAndAnIsolatedPowerSpikeAreAccepted() {
        (0..6).forEach { assertNull(add(it, rpm = if (it % 2 == 0) 77.0 else 83.0, pedal = if (it == 5) 260 else 190)) }
        assertNotNull(add(7, rpm = 80.0))
        assertEquals(CapturePhase.Saved, window.progress.phase)
    }
    @Test fun aPowerRampStillCannotBecomeCalibrationData() {
        (0..7).forEach { assertNull(add(it, pedal = 100 + it * 20)) }
        assertEquals(CapturePhase.PowerChanged, window.progress.phase)
    }
    @Test fun easyPedalingAtTwelvePedalWattsIsCapturedAndUsedByTheLocalFit() {
        var sample: CalibrationSample? = null
        (0..7).forEach { second ->
            val now = second * 1000L
            sample = window.add(CalibrationFrame(now, 1500, 75.0, 15.0, "bike", 1, 15),
                12 + (second % 3 - 1), now, "pedals", now, now)
            if (second < 7) assertNull(sample)
        }
        val accepted = sample!!
        assertEquals(12.0, accepted.pedalWatts, 0.0)
        assertEquals(15.0, accepted.nativeWatts, 0.0)
        val fit = GrupettoCalibrationFitter.fit(baseline, listOf(accepted))!!
        assertEquals(1, fit.sampleCount)
        assertTrue(fit.corrected(15.0, 1500.0, 75.0) < 15.0)
        assertFalse(fit.ready) // A single low-power cell cannot activate an entire table.
    }
    @Test fun zeroPowerAndLargeLowPowerRampsDoNotBecomeCalibrationSamples() {
        for ((native, pedal) in listOf(0.0 to 12, 15.0 to 0, 4.0 to 12, 15.0 to 4)) {
            assertNull(window.add(CalibrationFrame(0, 1500, 75.0, native, "bike", 1), pedal, 0, "pedals", 0, 0))
            assertEquals(CapturePhase.LowPower, window.progress.phase)
        }
        (0..7).forEach { second ->
            val now = second * 1000L
            assertNull(window.add(CalibrationFrame(now, 1500, 75.0, 15.0, "bike", 1),
                5 + second * 3, now, "pedals", now, now))
        }
        assertEquals(CapturePhase.PowerChanged, window.progress.phase)
    }
}
