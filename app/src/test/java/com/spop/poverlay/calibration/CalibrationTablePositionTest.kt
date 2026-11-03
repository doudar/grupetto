package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationTablePositionTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val frame = CalibrationFrame(1000, 2000, 80.0, 200.0, "bike", 1)
    @Test fun currentCellDoesNotRequireRecordingOrAnExistingTable() {
        val position = CalibrationTablePosition(frame, baseline)
        assertEquals(10 to 7, liveTableCell(position, null, 1000))
        assertEquals(10 to 7, liveTableCell(position, baseline, 3000))
        assertNull(liveTableCell(position, baseline, 3001))
        assertNull(liveTableCell(position, baseline, 999))
    }
    @Test fun invalidOrUnrelatedBikeDataDoesNotShowALiveCell() {
        listOf(frame.copy(rpm = 0.0), frame.copy(rpm = Double.NaN), frame.copy(ad = 999),
            frame.copy(powerSource = 2)).forEach {
            assertNull(liveTableCell(CalibrationTablePosition(it, baseline), baseline, 1000))
        }
        assertNull(liveTableCell(CalibrationTablePosition(frame, baseline), baseline.map { it + 1 }, 1000))
    }

    private fun table(filled: Set<Int>) = GrupettoCalibrationTable(baseline,
        List(496) { .1 }, List(496) { if (it in filled) 1.0 else 0.0 },
        filled.size, filled.size, 0.0, 0.0, true, recordedCoverage = filled,
        cellSampleCounts = List(496) { if (it in filled) 1 else 0 })

    @Test fun pausedCurrentCellAndNearestFallbackAreHighlightedSeparately() {
        val table = table(setOf(9 * 16 + 7))
        val result = liveTableHighlights(CalibrationTablePosition(frame, baseline), table, 1000)!!
        assertEquals(10 to 7, result.current)
        assertEquals(9 to 7, result.fallback)
        val source = table.lookupCellIndex(frame.ad.toDouble(), frame.rpm)!!
        assertEquals(source / 16 to source % 16, result.fallback)
    }

    @Test fun filledCurrentCellDoesNotGetASeparateFallbackBorder() {
        val result = liveTableHighlights(CalibrationTablePosition(frame, baseline),
            table(setOf(10 * 16 + 7, 9 * 16 + 7)), 1000)!!
        assertEquals(10 to 7, result.current)
        assertNull(result.fallback)
    }

    @Test fun currentCellStillHighlightsWithoutAnySavedData() {
        val position = CalibrationTablePosition(frame, baseline)
        listOf(null, table(emptySet())).forEach {
            val result = liveTableHighlights(position, it, 1000)!!
            assertEquals(10 to 7, result.current)
            assertNull(result.fallback)
        }
    }

    @Test fun staleOrDifferentBikePositionsClearBothHighlights() {
        val position = CalibrationTablePosition(frame, baseline)
        val table = table(setOf(9 * 16 + 7))
        assertNull(liveTableHighlights(position, table, 3001))
        assertNull(liveTableHighlights(position, table.copy(baseline = baseline.map { it + 1 }), 1000))
        assertNull(liveTableHighlights(null, table, 1000))
    }
}
