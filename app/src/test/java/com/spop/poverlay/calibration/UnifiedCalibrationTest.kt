package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Recordings and edits are sequential changes to one authoritative persisted surface. */
class UnifiedCalibrationTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val selected = 12 * 16 + 7
    private fun sample(time: Long = 10, native: Double = 200.0, pedals: Double = 220.0) =
        CalibrationSample(baseline[12].toDouble(), 80.0, native, pedals, time)
    private fun profile(samples: List<CalibrationSample> = listOf(sample())): GrupettoCalibrationProfile {
        val capture = CalibrationCapture("bike", "pedals", 2040490, baseline, samples)
        return updateUnifiedCalibration(capture, null)
    }
    private fun edit(profile: GrupettoCalibrationProfile, percent: Double, now: Long = 100) =
        applyUnifiedManualEdit(profile,
            CalibrationManualEdit.setPercent(profile.table!!, setOf(selected), percent, true), now)
    private fun assertAt(table: GrupettoCalibrationTable, sample: CalibrationSample, expected: Double = sample.pedalWatts) {
        assertEquals(expected, table.corrected(sample.nativeWatts, sample.ad, sample.rpm), 1e-8)
    }

    @Test fun editChangesSavedTargetsAndRetainsOriginalPedalEvidence() {
        val original = profile()
        val edited = edit(original, -10.0)
        val saved = edited.capture.samples.single()
        assertEquals(180.0, saved.pedalWatts, 1e-8)
        assertEquals(220.0, saved.recordedPedalWatts!!, 0.0)
        assertEquals(original.capture.samples.single().capturedAt, saved.capturedAt)
        assertAt(edited.table!!, saved)
        assertTrue(edited.table!!.recordedThrough >= 100)
    }

    @Test fun repeatedEditsNeverReplaceOriginalPedalAuditWithAnEarlierEditedTarget() {
        val first = edit(profile(), -10.0, 100)
        val again = edit(first, -10.0, 101)
        assertEquals(first.table!!.gains, again.table!!.gains)
        val changed = edit(again, 15.0, 102)
        assertEquals(230.0, changed.capture.samples.single().pedalWatts, 1e-8)
        assertEquals(220.0, changed.capture.samples.single().recordedPedalWatts!!, 0.0)
    }

    @Test fun futureRecordingReplacesManualValueRatherThanAddingAnOffset() {
        val edited = edit(profile(), -10.0)
        val fresh = sample(time = 101, pedals = 240.0)
        val next = updateUnifiedCalibration(edited.capture.copy(samples = edited.capture.samples + fresh), edited)
        assertAt(next.table!!, fresh)
        assertTrue(next.table!!.ready)
        assertTrue(next.table!!.recordedThrough >= fresh.capturedAt)
        assertEquals(220.0, next.capture.samples.first().recordedPedalWatts!!, 0.0)
    }

    @Test fun restartingOrRefittingWithoutNewSamplesPreservesEveryEditedGridVertex() {
        val edited = edit(profile(), -10.0)
        val filled = applyUnifiedManualEdit(edited, CalibrationManualEdit.fillGaps(edited.table!!), 101)
        val updated = updateUnifiedCalibration(filled.capture, filled)
        assertEquals(filled.table!!.gains, updated.table!!.gains)
        assertEquals(filled.table!!.ready, updated.table!!.ready)
        assertEquals(filled.table!!.recordedThrough, updated.table!!.recordedThrough)
    }

    @Test fun historicalUnseenAndDuplicateObservationsCannotReplayOverNewerEdit() {
        val edited = edit(profile(), -10.0, 100)
        val history = listOf(sample(time = 1, pedals = 260.0), sample(time = 50, pedals = 260.0),
            sample(time = 100, pedals = 260.0))
        val replayed = updateUnifiedCalibration(edited.capture.copy(samples = edited.capture.samples + history), edited)
        assertEquals(edited.table!!.gains, replayed.table!!.gains)
        val duplicate = updateUnifiedCalibration(replayed.capture, replayed)
        assertEquals(edited.table!!.gains, duplicate.table!!.gains)
    }

    @Test fun rejectedFutureSampleDoesNotAdvanceTheAcceptedObservationWatermark() {
        val edited = edit(profile(), -10.0, 100)
        val invalid = sample(time = 1000, pedals = 450.0) // Valid capture powers, rejected fitter ratio.
        val rejected = updateUnifiedCalibration(edited.capture.copy(samples = edited.capture.samples + invalid), edited)
        assertEquals(100L, rejected.table!!.recordedThrough)
        val fresh = sample(time = 101, pedals = 240.0)
        val next = updateUnifiedCalibration(rejected.capture.copy(samples = rejected.capture.samples + fresh), rejected)
        assertAt(next.table!!, fresh)
    }

    @Test fun savingAnOlderEditedSnapshotCannotRewindTheProfileWatermark() {
        val original = profile()
        val advanced = original.copy(table = original.table!!.copy(recordedThrough = 500))
        val olderEdited = CalibrationManualEdit.setPercent(original.table!!, setOf(selected), -10.0, true)
        val saved = applyUnifiedManualEdit(advanced, olderEdited, 100)
        assertEquals(500L, saved.table!!.recordedThrough)
    }

    @Test fun multipleFutureObservationsAreAppliedInTimestampOrderNotInputOrder() {
        val edited = edit(profile(), -10.0)
        val older = sample(time = 101, pedals = 160.0)
        val newest = sample(time = 102, pedals = 250.0)
        val next = updateUnifiedCalibration(edited.capture.copy(samples = listOf(newest, older) + edited.capture.samples), edited)
        assertAt(next.table!!, newest)
    }

    @Test fun activeSnapshotIsOnlyADiagnosticComparatorNotTheSurfaceSeed() {
        val original = profile()
        val edited = edit(original, -10.0)
        val next = updateUnifiedCalibration(edited.capture, edited, active = original.table)
        assertEquals(edited.table!!.gains, next.table!!.gains)
        assertAt(next.table!!, sample(), 180.0)
    }

    @Test fun resetBecomesTheUnifiedUsableSurfaceAndFutureRecordingCanChangeIt() {
        val edited = edit(profile(), -10.0)
        val reset = applyUnifiedManualEdit(edited, CalibrationManualEdit.resetCorrections(edited.table!!), 110)
        assertTrue(reset.table!!.gains.all { it == 0.0 })
        assertTrue(reset.table!!.ready)
        assertEquals(200.0, reset.capture.samples.single().pedalWatts, 0.0)
        assertEquals(220.0, reset.capture.samples.single().recordedPedalWatts!!, 0.0)
        assertSame(reset, activationProfile(reset, edited))
        val noNew = updateUnifiedCalibration(reset.capture, reset)
        assertEquals(reset.table!!.gains, noNew.table!!.gains)
        val fresh = sample(time = 111, pedals = 230.0)
        val next = updateUnifiedCalibration(reset.capture.copy(samples = reset.capture.samples + fresh), reset)
        assertAt(next.table!!, fresh)
    }

    @Test fun editedTargetsOutsideCapturePowerRangeRemainUsableWithValidOriginalEvidence() {
        for ((watts, percent) in listOf(5.0 to -35.0, 1500.0 to 35.0)) {
            val original = profile(listOf(sample(native = watts, pedals = watts)))
            val edited = edit(original, percent)
            val target = edited.capture.samples.single()
            assertEquals(watts * (1 + percent / 100), target.pedalWatts, 1e-8)
            assertEquals(watts, target.recordedPedalWatts!!, 0.0)
            val rebuilt = GrupettoCalibrationFitter.fit(baseline, edited.capture.samples)
            assertNotNull(rebuilt)
            assertAt(rebuilt!!, target)
        }
    }

    @Test fun displayErrorsRemainAgainstActualPedalsAfterTargetsAreEdited() {
        val original = profile()
        val edited = edit(original, -10.0)
        val error = calibrationErrorComparison(edited, null, null)!!
        assertEquals(20.0, error.beforeWatts, 1e-8)
        assertEquals(40.0, error.afterWatts!!, 1e-8)
    }

    @Test fun localNewRecordingPreservesDistantManualShapeInsteadOfRebuildingTheWholeTable() {
        val original = profile()
        val far = 30 * 16 + 15
        val surface = CalibrationManualEdit.setPercent(original.table!!, setOf(far), -20.0, false)
        val edited = applyUnifiedManualEdit(original, surface, 100)
        val fresh = sample(time = 101, pedals = 240.0)
        val next = updateUnifiedCalibration(edited.capture.copy(samples = edited.capture.samples + fresh), edited)
        assertAt(next.table!!, fresh)
        assertTrue(abs(next.table!!.gains[far] - edited.table!!.gains[far]) < 1e-10)
    }
}
