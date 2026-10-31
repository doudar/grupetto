package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CalibrationManualEditTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val samples = (8..16 step 2).flatMap { x -> listOf(65, 80, 95).map { rpm ->
        CalibrationSample(baseline[x].toDouble(), rpm.toDouble(), 200.0, 220.0, (x * 100 + rpm).toLong())
    } }
    private fun table() = GrupettoCalibrationFitter.fit(baseline, samples)!!

    @Test fun manualCellIsExactBlendsSmoothlyAndDoesNotInventEvidence() {
        val original = table()
        val cell = 8 * 16 + 7
        val edited = CalibrationManualEdit.setPercent(original, setOf(cell), -12.5, true)
        assertEquals(175.0, edited.corrected(200.0, baseline[8].toDouble(), 80.0), 1e-9)
        val centerDelta = edited.gains[cell] - original.gains[cell]
        assertEquals(centerDelta * exp(-.5 / 2.5.pow(2)), edited.gains[cell + 16] - original.gains[cell + 16], 1e-9)
        assertEquals(centerDelta * exp(-.5 / 3.0.pow(2)), edited.gains[cell + 1] - original.gains[cell + 1], 1e-9)
        assertEquals(original.sampleCount, edited.sampleCount)
        assertEquals(original.confidence, edited.confidence)
        assertEquals(original.cells, edited.cells)
        assertEquals(original.recordedCoverage, edited.recordedCoverage)
        assertEquals(original.beforeRmse, edited.beforeRmse, 0.0)
        assertEquals(original.afterRmse, edited.afterRmse, 0.0)
        assertTrue(cell in edited.manualCoverage)
    }

    @Test fun precisionEditsAndAllSelectionScopesStayWithinTheirBounds() {
        val original = table()
        val cell = 0
        CalibrationEditScope.values().forEach { scope ->
            val selection = scope.indices(cell)
            val edited = CalibrationManualEdit.setPercent(original, selection, 35.0, false)
            original.gains.indices.forEach { index ->
                assertEquals(if (index in selection) ln(1.35) else original.gains[index], edited.gains[index], 1e-12)
            }
        }
        assertEquals(1, CalibrationEditScope.Cell.indices(cell).size)
        assertEquals(31, CalibrationEditScope.Cadence.indices(cell).size)
        assertEquals(16, CalibrationEditScope.Resistance.indices(cell).size)
        assertEquals(496, CalibrationEditScope.All.indices(cell).size)
        val lowered = CalibrationManualEdit.nudge(CalibrationManualEdit.setPercent(original, setOf(cell), -35.0, false), setOf(cell), -1.0, false)
        assertEquals(ln(.65), lowered.gains[cell], 1e-12)
        assertTrue(runCatching { CalibrationManualEdit.setPercent(original, setOf(cell), Double.NaN, true) }.isFailure)
        assertTrue(runCatching { CalibrationManualEdit.setPercent(original, setOf(cell), 35.1, true) }.isFailure)
    }

    @Test fun fillingPreservesKnownCellsAndExtendsConstantCalibrationToEveryEdge() {
        val original = table()
        val constant = original.copy(gains = List(496) { ln(1.1) }, correctionPercent = original.correctionPercent.map { it?.let { 10.0 } })
        val filled = CalibrationManualEdit.fillGaps(constant)
        assertEquals(496, filled.correctionPercent.count { it != null })
        assertTrue(filled.gains.all { abs(it - ln(1.1)) < 1e-12 })
        original.correctionPercent.indices.filter { original.correctionPercent[it] != null }.forEach {
            assertEquals(constant.gains[it], filled.gains[it], 0.0)
        }
        assertEquals(constant.recordedCoverage, filled.recordedCoverage)
        assertEquals(filled, CalibrationManualEdit.fillGaps(filled))
        val unknown = original.copy(correctionPercent = List(496) { null })
        assertEquals(unknown, CalibrationManualEdit.fillGaps(unknown))
    }

    @Test fun smoothingAndSofteningAreBoundedAndRespectSelection() {
        val cell = 15 * 16 + 7
        val original = CalibrationManualEdit.setPercent(table(), setOf(cell), 35.0, false)
        val smooth = CalibrationManualEdit.smooth(original, setOf(cell))
        assertTrue(smooth.gains[cell] < original.gains[cell])
        val soft = CalibrationManualEdit.soften(original, setOf(cell))
        assertEquals(28.0, CalibrationManualEdit.percent(soft.gains[cell]), 1e-9)
        original.gains.indices.filter { it != cell }.forEach {
            assertEquals(original.gains[it], smooth.gains[it], 0.0)
            assertEquals(original.gains[it], soft.gains[it], 0.0)
        }
        assertTrue(CalibrationManualEdit.smooth(original, (0 until 496).toSet()).gains.all { it in ln(.65)..ln(1.35) })
    }

    @Test fun restoreAndRefitPreserveTheRecordedFitAndManualLayerSeparately() {
        val original = table()
        val edited = CalibrationManualEdit.fillGaps(CalibrationManualEdit.setPercent(original, setOf(15 * 16 + 7), -8.0, true))
        val restored = CalibrationManualEdit.resetToRecorded(edited)
        original.gains.indices.forEach { assertEquals(original.gains[it], restored.gains[it], 1e-12) }
        assertTrue(restored.manualCoverage.isEmpty())
        assertEquals(original.correctionPercent.map { it != null }, restored.correctionPercent.map { it != null })
        val nextFit = GrupettoCalibrationFitter.fit(baseline, samples.map { it.copy(pedalWatts = 210.0) })!!
        val reapplied = CalibrationManualEdit.reapply(nextFit, edited)
        nextFit.gains.indices.forEach {
            assertEquals((nextFit.gains[it] + edited.manualOffsets[it]).coerceIn(ln(.65), ln(1.35)), reapplied.gains[it], 1e-12)
        }
        assertEquals(edited.manualCoverage, reapplied.manualCoverage)
        assertEquals(nextFit, CalibrationManualEdit.reapply(nextFit, edited.copy(baseline = baseline.map { it + 1 })))
    }

    @Test fun undoRedoAndNewBranchRestoreCompleteSurfaces() {
        val original = table()
        val history = CalibrationEditHistory(original)
        val first = history.commit(CalibrationManualEdit.setPercent(original, setOf(0), -10.0, true))
        val second = history.commit(CalibrationManualEdit.fillGaps(first))
        assertEquals(first, history.undo())
        assertEquals(original, history.undo())
        assertFalse(history.canUndo)
        assertEquals(first, history.redo())
        assertEquals(second, history.redo())
        history.undo()
        history.commit(CalibrationManualEdit.soften(first, setOf(0)))
        assertFalse(history.canRedo)
    }
}
