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
}
