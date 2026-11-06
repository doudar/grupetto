package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationActivationTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val sample = CalibrationSample(2000.0, 80.0, 200.0, 220.0, 1)
    private fun profile(samples: List<CalibrationSample> = listOf(sample)): GrupettoCalibrationProfile {
        val table = GrupettoCalibrationFitter.fit(baseline, listOf(sample))!!
            .copy(sampleCount = samples.size)
        return GrupettoCalibrationProfile(CalibrationCapture("bike", "meter", 2040490L, baseline, samples), table)
    }

    @Test fun oneValidPointIsReadyAndSuppliesNearestFilledCorrection() {
        val one = profile()
        assertTrue(one.table!!.ready)
        assertTrue(one.canActivate)
        assertSame(one, activationProfile(one, null))
        assertNull(automaticCalibrationProfile(one, null)) // First activation remains explicit.
        assertSame(one, automaticCalibrationProfile(one, one))
        assertEquals(220.0, one.table.corrected(200.0, 4000.0, 120.0), 1e-9)
    }

    @Test fun oldSparseReadyFlagIsUpgradedWithoutReplayingOrChangingGains() {
        val old = profile().let { it.copy(table = it.table!!.copy(ready = false)) }
        val upgraded = old.withActivationReadiness()
        assertTrue(upgraded.table!!.canActivate)
        assertEquals(old.capture, upgraded.capture)
        assertEquals(old.table!!.gains, upgraded.table.gains)
        assertEquals(old.table.cellSampleCounts, upgraded.table.cellSampleCounts)
        assertSame(old, activationProfile(old, null))
    }

    @Test fun zeroPointsCannotActivateEvenWithAStaleReadyBitOrManualCells() {
        val empty = profile(emptyList())
        assertFalse(empty.table!!.canActivate)
        assertFalse(empty.canActivate)
        assertNull(activationProfile(empty, null))
        assertNull(automaticCalibrationProfile(empty, empty))
        assertFalse(empty.withActivationReadiness().table!!.ready)
        assertNull(GrupettoCalibrationFitter.fit(baseline, emptyList()))
    }

    @Test fun invalidPointOrUnpopulatedOrMalformedTableCannotActivate() {
        val invalid = listOf(sample.copy(nativeWatts = 0.0), sample.copy(pedalWatts = 4.0),
            sample.copy(rpm = 0.0), sample.copy(ad = Double.NaN), sample.copy(pedalWatts = 450.0))
        (invalid + sample.copy(observation = CalibrationObservation(0, 0, 100000))).forEach { effort ->
            val bad = profile(listOf(effort))
            assertFalse(bad.canActivate)
            assertFalse(bad.withActivationReadiness().table!!.canActivate)
            assertNull(activationProfile(bad, null))
            assertNull(GrupettoCalibrationFitter.fit(baseline, listOf(effort)))
        }
        val good = profile()
        listOf(good.table!!.copy(cellSampleCounts = List(496) { 0 }),
            good.table.copy(gains = List(496) { Double.NaN }),
            good.table.copy(baseline = List(31) { 1000 }),
            good.table.copy(cellSampleCounts = listOf(1))).forEach { table ->
            assertFalse(good.copy(table = table).canActivate)
        }
    }
}
