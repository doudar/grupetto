package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationGuidanceTest {
    private val baseline = List(31) { 1000 + it * 100 }

    @Test fun easierAndHarderShiftTheSameBroadTargetsAndStopAtTableBounds() {
        val guide = CalibrationGuide(baseline, 2000)
        val original = guide.cells(emptyList()).map { it.point }
        guide.selected = 8 to 75
        guide.harder()
        assertNull(guide.selected)
        assertEquals(original.map { it + 2 }, guide.cells(emptyList()).map { it.point })
        guide.easier()
        assertEquals(original, guide.cells(emptyList()).map { it.point })
        repeat(30) { guide.harder() }
        assertEquals(listOf(25, 27, 29), guide.cells(emptyList()).filter { it.rpm == 75 }.map { it.point })
        assertEquals(baseline.last(), guide.cells(emptyList()).maxOf { it.upperAd })
        repeat(30) { guide.easier() }
        assertEquals(listOf(1, 3, 5), guide.cells(emptyList()).filter { it.rpm == 75 }.map { it.point })
        assertEquals(baseline.first(), guide.cells(emptyList()).minOf { it.lowerAd })
    }

    @Test fun wattEstimatesInterpolateNearbyPedalReadingsAndAdjustForCadence() {
        val guide = CalibrationGuide(baseline, 2000)
        val samples = listOf(50.0, 50.0, 999.0).mapIndexed { i, watts ->
            CalibrationSample(baseline[8].toDouble(), 75.0, 200.0, watts, i.toLong())
        } + CalibrationSample(baseline[12].toDouble(), 75.0, 200.0, 150.0, 4)
        val cells = guide.cells(samples)
        assertEquals(50, cells.first { it.point == 8 && it.rpm == 75 }.approximateWatts)
        assertEquals(100, cells.first { it.point == 10 && it.rpm == 75 }.approximateWatts)
        assertEquals(113, cells.first { it.point == 10 && it.rpm == 85 }.approximateWatts)
        assertEquals(3, cells.first { it.point == 8 && it.rpm == 75 }.repeats)
    }

    @Test fun unmeasuredTargetsAndStoppedPedalsDoNotGetInventedWatts() {
        val target = CalibrationTarget(20, baseline[20], 75, 0, baseline[19], baseline[21])
        val far = CalibrationSample(baseline[8].toDouble(), 75.0, 200.0, 50.0, 1)
        assertNull(estimateTargetWatts(baseline, target, emptyList()))
        assertNull(estimateTargetWatts(baseline, target, listOf(far)))
        assertNull(estimateTargetWatts(baseline, target, listOf(far.copy(ad = target.ad.toDouble(), pedalWatts = 0.0))))
    }
}
