package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class CalibrationCoverageRegressionTest {
    @Test fun savedLowCadenceEffortsAreNoLongerDroppedAt64Cells() {
        val lines = javaClass.getResourceAsStream("/calibration/low-cadence-coverage.csv")!!
            .bufferedReader().use { it.readLines() }
        val baseline = lines.first().split(',').map { it.toInt() }
        val samples = lines.drop(1).mapIndexed { i, line ->
            val v = line.split(',').map { it.toDouble() }
            CalibrationSample(v[0], v[1], v[2], v[3], i.toLong())
        }
        val fit = GrupettoCalibrationFitter.fit(baseline, samples)!!
        assertEquals(70, fit.cells)
        assertEquals(198, fit.sampleCount)
        val lowCadence = samples.filter { it.ad == 815.0 && it.rpm < 60 }
        assertEquals(2, lowCadence.size)
        val before = lowCadence.map { abs(it.nativeWatts - it.pedalWatts) }.average()
        val after = lowCadence.map { abs(fit.corrected(it.nativeWatts, it.ad, it.rpm) - it.pedalWatts) }.average()
        println("Saved low-cadence error: $before -> $after W; 140 W at AD815/60rpm -> ${fit.corrected(140.0, 815.0, 60.0)} W")
        println("All captured MAE: ${samples.map { abs(it.nativeWatts - it.pedalWatts) }.average()} -> " +
            "${samples.map { abs(fit.corrected(it.nativeWatts, it.ad, it.rpm) - it.pedalWatts) }.average()}; " +
            "validation RMSE ${fit.beforeRmse} -> ${fit.afterRmse}; ready=${fit.ready}")
        assertTrue(after < before / 2)
        assertTrue(fit.ready)
    }

    @Test fun largeCoverageCoarsensWithoutLosingAnyValidationEfforts() {
        val baseline = List(31) { 1000 + it * 100 }
        val samples = (0..30).flatMap { x -> (45..120 step 5).map { rpm ->
            CalibrationSample(baseline[x].toDouble(), rpm.toDouble(), 100.0, 110.0, (x * 1000 + rpm).toLong())
        } }
        val active = GrupettoCalibrationTable(baseline, List(496) { 0.0 }, List(496) { 1.0 }, 0, 0, 0.0, 0.0, true)
        val fit = GrupettoCalibrationFitter.fit(baseline, samples, active)!!
        assertTrue(fit.cells in 6..128)
        assertEquals(samples.size, fit.sampleCount)
        assertEquals(samples.size, fit.activeComparison!!.count)
        assertTrue(fit.gains.all { it.isFinite() })
        assertTrue(fit.ready)
    }
}
