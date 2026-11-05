package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationStreamSettingsTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun add(stream: CalibrationStream, t: Long, rpm: Double = 95.0, ad: Int = 2000,
        pedal: Int = 220, resistance: Int = 40, native: Double = 200.0) =
        stream.add(CalibrationFrame(t, ad, rpm, native, "bike", 1, resistance), pedal, t,
            "meter", t, 100000 + t)

    @Test fun plusMinusFiveCrossesCellsWithoutRestartButKeepsExactCadenceAndActualCell() {
        val stream = CalibrationStream(baseline)
        add(stream, 0); add(stream, 1000); assertEquals(1, add(stream, 2000).size)
        val high = add(stream, 3000, rpm = 100.0).single()
        val low = add(stream, 4000, rpm = 90.0).single()
        assertEquals(100.0, high.rpm, 0.0); assertEquals(90.0, low.rpm, 0.0)
        assertEquals(10 * 16 + 9, stream.progress.cell)
        assertTrue(add(stream, 5000, rpm = 101.0).isEmpty())
        assertEquals("Cadence outside window", stream.progress.detail)
        assertTrue(add(stream, 6000, rpm = 101.0).isEmpty())
        assertEquals(1, add(stream, 7000, rpm = 101.0).size)
    }

    @Test fun resistanceWindowUsesRawSignalInsteadOfIntegerDisplayBoundary() {
        val stream = CalibrationStream(baseline, settings = CalibrationStreamSettings(resistanceWindowPercent = 10))
        add(stream, 0); add(stream, 1000); add(stream, 2000)
        assertEquals(1, add(stream, 3000, ad = 2009, resistance = 41).size)
        assertTrue(add(stream, 4000, ad = 2011, resistance = 41).isEmpty())
        assertEquals("Resistance outside window", stream.progress.detail)
    }

    @Test fun settleTimeAndMidHoldSettingsUpdatesApplyWithoutRecountingCachedPackets() {
        val stream = CalibrationStream(baseline, settings = CalibrationStreamSettings(settleSeconds = 4))
        (0L..3000L step 1000).forEach { assertTrue(add(stream, it).isEmpty()) }
        assertEquals(1, add(stream, 4000).size)
        stream.updateSettings(stream.settings.copy(settleSeconds = 0))
        assertTrue(add(stream, 4000).isEmpty())
        assertEquals(1, add(stream, 5000).size)
    }

    @Test fun percentToleranceScalesWithPowerAndConfirmationCountIsConfigurable() {
        val stream = CalibrationStream(baseline, settings = CalibrationStreamSettings(settleSeconds = 0,
            outlierTolerancePercent = 5.0, newLevelReadings = 2))
        assertEquals(1, add(stream, 0, native = 500.0, pedal = 550).size)
        assertEquals(1, add(stream, 1000, native = 500.0, pedal = 570).size) // 4pp / 20W accepted.
        assertTrue(add(stream, 2000, native = 500.0, pedal = 625).isEmpty())
        val changed = add(stream, 3000, native = 500.0, pedal = 625)
        assertEquals(2, changed.size)
        assertTrue(changed.first().observation!!.startsNewLevel)
    }

    @Test fun disablingOutlierGateNeverDisablesFreshnessPowerOrPacketChecks() {
        val stream = CalibrationStream(baseline, settings = CalibrationStreamSettings(settleSeconds = 0, outlierRejection = false))
        assertEquals(1, add(stream, 0).size)
        assertEquals(1, add(stream, 1000, pedal = 300).size)
        assertTrue(add(stream, 1000, pedal = 300).isEmpty())
        assertTrue(add(stream, 2000, pedal = 0).isEmpty())
        assertEquals(CapturePhase.LowPower, stream.progress.phase)
        assertTrue(stream.add(CalibrationFrame(0, 2000, 95.0, 200.0, "bike", 1),
            220, 3000, "meter", 3000, 103000).isEmpty())
    }

    @Test fun invalidPreferencesAndNonFiniteInputsFallBackOrAreRejected() {
        assertEquals(CalibrationStreamSettings(), CalibrationStreamSettings.fromStored(mapOf(
            "settleSeconds" to -1, "cadenceWindowRpm" to Double.NaN, "outlierTolerancePercent" to 100,
            "outlierNoiseMultiplier" to "bad", "newLevelReadings" to 1)))
        listOf({ CalibrationStreamSettings(cadenceWindowRpm = Double.NaN) },
            { CalibrationStreamSettings(outlierTolerancePercent = Double.POSITIVE_INFINITY) },
            { CalibrationStreamSettings(settleSeconds = 16) }, { CalibrationStreamSettings(newLevelReadings = 1) })
            .forEach { assertTrue(runCatching(it).isFailure) }
    }

    @Test fun outlierConfirmationsSurviveNearbyCellCrossingsWithoutMixingCells() {
        fun past(rpm: Double) = CalibrationSample(2000.0, rpm, 200.0, 220.0, 1,
            observation = CalibrationObservation(0, 0))
        val stream = CalibrationStream(baseline, listOf(past(95.0), past(100.0)),
            CalibrationStreamSettings(settleSeconds = 0))
        assertTrue(add(stream, 0, pedal = 250).isEmpty())
        assertTrue(add(stream, 1000, rpm = 100.0, pedal = 250).isEmpty())
        assertTrue(add(stream, 2000, pedal = 250).isEmpty())
        assertTrue(add(stream, 3000, rpm = 100.0, pedal = 250).isEmpty())
        val low = add(stream, 4000, pedal = 250)
        val high = add(stream, 5000, rpm = 100.0, pedal = 250)
        assertEquals(3, low.size); assertEquals(3, high.size)
        assertTrue(low.all { it.rpm == 95.0 }); assertTrue(high.all { it.rpm == 100.0 })
        assertTrue(low.first().observation!!.startsNewLevel)
    }

    @Test fun oldPendingOutliersExpireEvenWhenOtherCellsKeepTheHoldAlive() {
        fun past(rpm: Double) = CalibrationSample(2000.0, rpm, 200.0, 220.0, 1,
            observation = CalibrationObservation(0, 0))
        val stream = CalibrationStream(baseline, listOf(past(95.0), past(100.0)),
            CalibrationStreamSettings(settleSeconds = 0))
        add(stream, 0, pedal = 250)
        add(stream, 1000, pedal = 250)
        (2000L..6000L step 1000).forEach { add(stream, it, rpm = 100.0) }
        assertTrue(add(stream, 7000, pedal = 250).isEmpty())
        assertEquals("Checking shifted ratio: 1/3", stream.progress.detail)
    }

    @Test fun zeroSettleFastRestartDoesNotRecountTheLastRecordedPacketPair() {
        val previous = CalibrationSample(2000.0, 95.0, 200.0, 220.0, 100000,
            observation = CalibrationObservation(0, 0))
        val stream = CalibrationStream(baseline, listOf(previous), CalibrationStreamSettings(settleSeconds = 0))
        assertTrue(add(stream, 0).isEmpty())
        assertEquals(1, add(stream, 1000).size)
    }
}
