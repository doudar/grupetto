package com.spop.poverlay.calibration

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.ln
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** A preview is read-only; applying and undoing replace complete snapshots, never raw evidence. */
class CalibrationRefitLifecycleTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val cell = 10 * 16 + 7

    private fun profile(ready: Boolean = false): GrupettoCalibrationProfile {
        // Targets reflect an earlier manual edit. Refit must use the immutable pedal readings.
        val samples = listOf(200.0, 200.0, 240.0).mapIndexed { index, measured ->
            CalibrationSample(2000.0, 80.0, 200.0, 250.0, 1000L + index,
                recordedPedalWatts = measured)
        }
        val table = GrupettoCalibrationTable(baseline,
            List(496) { if (it == cell) ln(1.25) else 0.0 },
            List(496) { if (it == cell) .3 else 0.0 }, samples.size, 1, 30.0, 40.0, ready,
            recordedThrough = 1002L, manualCoverage = setOf(cell),
            cellSampleCounts = List(496) { if (it == cell) 3 else 0 })
            .withCalibrationCoverage(samples)
        return GrupettoCalibrationProfile(CalibrationCapture("refit-bike", "refit-meter", 2040490L,
            baseline, samples), table)
    }

    private fun isolated(block: suspend (GrupettoCalibrationManager, Context) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "refit-lifecycle-${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$id-$name", mode)
        }
        try {
            withContext(Dispatchers.Main) { block(GrupettoCalibrationManager(isolated), isolated) }
        } finally {
            directory.deleteRecursively()
            isolated.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private suspend fun load(manager: GrupettoCalibrationManager, ready: Boolean = false) {
        val profile = profile(ready)
        manager.importBackup(CalibrationBackup(profile, if (ready) profile else null))
        manager.verifyBaseline(baseline)
    }

    @Test fun previewApplyAndUndoPreserveEvidenceReadinessAndExactSnapshots() = isolated { manager, context ->
        load(manager)
        val original = manager.exportBackup()
        val originalDisplay = manager.state.value.displayTable!!
        val preview = manager.prepareRefit()
        assertEquals(original, manager.exportBackup())
        assertEquals(originalDisplay, manager.state.value.displayTable)
        assertFalse(manager.state.value.active)
        assertFalse(manager.state.value.recording)

        manager.applyRefit(preview)
        val applied = CalibrationBackup.decode(manager.exportBackup())
        val table = applied.draft!!.table!!
        assertEquals(profile().capture, applied.draft.capture)
        assertEquals(200.0, table.corrected(200.0, 2000.0, 80.0), 1e-9)
        assertEquals(3, table.cellSampleCounts[cell])
        assertEquals(originalDisplay.manualCoverage, table.manualCoverage)
        assertEquals(originalDisplay.recordedThrough, table.recordedThrough)
        assertTrue(table.canActivate)
        assertTrue(manager.state.value.canActivate)
        assertFalse(manager.state.value.active)
        assertFalse(manager.state.value.recording)
        assertNull(applied.saved)
        val restarted = GrupettoCalibrationManager(context)
        assertEquals(manager.exportBackup(), restarted.exportBackup())

        manager.undoRefit(preview)
        assertEquals(original, manager.exportBackup())
        assertEquals(originalDisplay, manager.state.value.displayTable)
        assertFalse(manager.state.value.active)
        assertTrue(runCatching { manager.undoRefit(preview) }.isFailure)
    }

    @Test fun activeRefitAndUndoRestoreSavedAndActiveTablesTogether() = isolated { manager, context ->
        load(manager, ready = true)
        manager.activate(true)
        withTimeout(5000) { while (!manager.state.value.active) delay(10) }
        val original = manager.exportBackup()
        val originalDisplay = manager.state.value.displayTable!!
        val preview = manager.prepareRefit()
        manager.applyRefit(preview)
        assertTrue(manager.state.value.active)
        val applied = CalibrationBackup.decode(manager.exportBackup())
        assertEquals(applied.draft!!.table!!.gains, applied.saved!!.table!!.gains)
        assertEquals(profile(true).capture.samples, applied.saved.capture.samples)
        assertEquals(200.0, manager.state.value.displayTable!!.corrected(200.0, 2000.0, 80.0), 1e-9)
        manager.undoRefit(preview)
        assertTrue(manager.state.value.active)
        assertEquals(original, manager.exportBackup())
        assertEquals(originalDisplay, manager.state.value.displayTable)
        val restarted = GrupettoCalibrationManager(context)
        assertEquals(original, restarted.exportBackup())
        assertTrue(restarted.state.value.active)
    }

    @Test fun recordingInvalidatesPreviewAndUndoAndUsesRefittedCountWeight() = isolated { manager, _ ->
        load(manager)
        val stale = manager.prepareRefit()
        manager.record(true, null)
        manager.record(false, null)
        assertTrue(runCatching { manager.applyRefit(stale) }.isFailure)
        val preview = manager.prepareRefit()
        manager.applyRefit(preview)
        val applied = CalibrationBackup.decode(manager.exportBackup()).draft!!
        manager.record(true, null)
        val fresh = CalibrationSample(2000.0, 80.0, 200.0, 240.0, System.currentTimeMillis() + 1000)
        manager.accept(applied.capture.copy(samples = applied.capture.samples + fresh))
        manager.record(false, null)
        val updated = CalibrationBackup.decode(manager.exportBackup()).draft!!
        assertEquals(210.0, updated.table!!.corrected(200.0, 2000.0, 80.0), 1e-8)
        assertEquals(4, updated.table.cellSampleCounts[cell])
        assertEquals(applied.capture.samples, updated.capture.samples.take(3))
        assertEquals(fresh, updated.capture.samples.last())
        val afterRecording = manager.exportBackup()
        assertTrue(runCatching { manager.undoRefit(preview) }.isFailure)
        assertEquals(afterRecording, manager.exportBackup())
    }

    @Test fun importClearAndBaselineChangesInvalidatePreparedPreview() = isolated { manager, _ ->
        load(manager)
        val beforeImport = manager.prepareRefit()
        load(manager)
        val imported = manager.exportBackup()
        assertTrue(runCatching { manager.applyRefit(beforeImport) }.isFailure)
        assertEquals(imported, manager.exportBackup())

        val beforeBaseline = manager.prepareRefit()
        manager.verifyBaseline(baseline.map { it + 1 })
        assertTrue(runCatching { manager.applyRefit(beforeBaseline) }.isFailure)
        manager.verifyBaseline(baseline)
        val beforeClear = manager.prepareRefit()
        manager.clear()
        withTimeout(5000) { while (manager.state.value.count != 0) delay(10) }
        assertTrue(runCatching { manager.applyRefit(beforeClear) }.isFailure)
        assertNull(manager.state.value.displayTable)
    }

    @Test fun refittingOlderActiveSnapshotKeepsNewerDraftConsumedAcrossRestart() = isolated { manager, context ->
        val saved = profile(ready = true)
        val newer = saved.capture.samples.last().copy(capturedAt = 2000L)
        val draft = saved.copy(capture = saved.capture.copy(samples = saved.capture.samples + newer),
            table = saved.table!!.copy(ready = false, recordedThrough = 2000L, sampleCount = 4,
                cellSampleCounts = List(496) { if (it == cell) 4 else 0 }))
        manager.importBackup(CalibrationBackup(draft, saved))
        // Resume a previously active saved snapshot. Making the newer sparse draft
        // eligible must not silently apply it during initialization.
        context.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE)
            .edit().putBoolean("active", true).commit()
        val resumed = GrupettoCalibrationManager(context)
        resumed.exportBackup()
        resumed.verifyBaseline(baseline)
        assertTrue(resumed.state.value.active)
        assertEquals(1002L, resumed.state.value.displayTable!!.recordedThrough)
        val preview = resumed.prepareRefit()
        resumed.applyRefit(preview)
        val applied = CalibrationBackup.decode(resumed.exportBackup())
        assertEquals(draft.capture.samples, applied.draft!!.capture.samples)
        assertTrue(applied.draft.table!!.recordedThrough >= 2000L)
        val restarted = GrupettoCalibrationManager(context)
        assertEquals(resumed.exportBackup(), restarted.exportBackup())
        resumed.record(true, null)
        resumed.record(false, null)
        val afterRecord = CalibrationBackup.decode(resumed.exportBackup())
        assertEquals(applied.draft.table!!.gains, afterRecord.draft!!.table!!.gains)
        assertEquals(applied.draft.table!!.cellSampleCounts, afterRecord.draft.table!!.cellSampleCounts)
    }

    @Test fun manualEditAndActivationChangeInvalidatePreparedPreview() = isolated { manager, _ ->
        load(manager, ready = true)
        val beforeManual = manager.prepareRefit()
        val expected = manager.state.value.displayTable!!
        val edited = CalibrationManualEdit.setPercent(expected, setOf(cell), 5.0, false)
        manager.saveManualEdit(expected, edited)
        val saved = manager.exportBackup()
        assertTrue(runCatching { manager.applyRefit(beforeManual) }.isFailure)
        assertEquals(saved, manager.exportBackup())
        val beforeActivation = manager.prepareRefit()
        manager.activate(true)
        withTimeout(5000) { while (!manager.state.value.active) delay(10) }
        assertTrue(runCatching { manager.applyRefit(beforeActivation) }.isFailure)
    }
}
