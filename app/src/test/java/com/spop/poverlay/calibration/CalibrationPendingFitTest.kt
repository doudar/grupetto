package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationPendingFitTest {
    private val table = GrupettoCalibrationTable(List(31) { 1000 + it * 100 }, List(496) { .05 },
        List(496) { 1.0 }, 30, 30, 10.0, 2.0, true)
    private fun pending(draft: GrupettoCalibrationTable? = table, displayed: GrupettoCalibrationTable? = table,
        active: Boolean = true, recording: Boolean = false, count: Int = 30) =
        hasPendingCalibrationFit(active, recording, draft, displayed, count, 30)

    @Test fun matchingActiveTableDoesNotNeedManualUpdate() { assertFalse(pending()) }
    @Test fun changedDiagnosticsAloneDoNotOfferUpdate() {
        assertFalse(pending(table.copy(beforeRmse = 15.0, afterRmse = 4.0, confidence = List(496) { 2.0 })))
    }
    @Test fun changedCorrectionAfterRefitOffersRecovery() {
        assertTrue(pending(table.copy(gains = List(496) { .06 })))
    }
    @Test fun pendingNewEffortsOfferRecoveryAfterRecordingStops() { assertTrue(pending(count = 31)) }
    @Test fun automaticRecordingDoesNotShowManualUpdate() { assertFalse(pending(recording = true, count = 31)) }
    @Test fun activationRemainsExplicitForInactiveDrafts() { assertFalse(pending(active = false, count = 31)) }
    @Test fun unreadyAndMissingTablesCannotOfferUpdate() {
        assertFalse(pending(table.copy(ready = false), count = 31))
        assertFalse(pending(null)); assertFalse(pending(displayed = null))
    }
    @Test fun savedManualEditsRemainPartOfPendingState() {
        assertTrue(pending(table.copy(manualCoverage = setOf(5))))
    }
}
