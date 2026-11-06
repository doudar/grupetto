package com.spop.poverlay.calibration

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.*

class CalibrationBackupTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun profile(sparse: Boolean = false): GrupettoCalibrationProfile {
        val samples = (8..16 step 2).flatMap { x -> listOf(65, 80, 95).map { rpm ->
            val watts = 80.0 + x * 8
            CalibrationSample(baseline[x].toDouble(), rpm.toDouble(), watts, watts * 1.1, (x * 100 + rpm).toLong())
        } }.let { if (sparse) it.take(1) else it }
        return GrupettoCalibrationProfile(CalibrationCapture("test-bike", "test-meter", 2040490, baseline, samples),
            GrupettoCalibrationFitter.fit(baseline, samples))
    }
    private fun read(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use(CalibrationBackup::read)

    @Test fun changingMetersMergesPendingPointsAndKeepsRecordingAfterEverySave() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "capture-meter-test-${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$id-$name", mode)
        }
        try {
            withContext(Dispatchers.Main) {
                val manager = GrupettoCalibrationManager(isolated)
                val original = profile()
                manager.importBackup(CalibrationBackup(original, original))
                manager.activate(true)
                withTimeout(5000) { while (!manager.state.value.active) delay(10) }
                val pending = original.capture.copy(meterAddress = "second-meter",
                    samples = listOf(original.capture.samples.first().copy(capturedAt = System.currentTimeMillis() - 1000)))
                manager.record(true, pending)
                assertEquals(original.capture.samples.size + 1, manager.state.value.count)
                repeat(3) { index ->
                    val point = pending.samples.first().copy(capturedAt = System.currentTimeMillis() + index)
                    manager.accept(pending.copy(samples = pending.samples + point))
                    assertTrue("Record must stay on after saving a point", manager.state.value.recording)
                    assertTrue(manager.state.value.active)
                    assertEquals(original.capture.samples.size + index + 2, manager.state.value.count)
                }
                val saved = read(manager.exportBackup())
                assertEquals("second-meter", saved.draft!!.capture.meterAddress)
                assertEquals(saved.draft.capture.samples.size, saved.saved!!.capture.samples.size)
                manager.record(false, null)
                manager.record(true, pending)
                assertEquals(saved.draft.capture.samples.size, manager.state.value.count)
                val incompatible = pending.copy(frameSerial = "another-bike", samples = listOf(
                    pending.samples.first().copy(capturedAt = System.currentTimeMillis() + 100)))
                assertTrue(runCatching { manager.accept(incompatible) }.isFailure)
                assertFalse(manager.state.value.recording)
            }
        } finally {
            directory.deleteRecursively()
            isolated.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun backupPreservesSparseDraftAndDistinctUsableSnapshot() {
        val backup = CalibrationBackup(profile(true), profile())
        val restored = read(backup.encode())
        assertEquals(backup.draft!!.capture, restored.draft!!.capture)
        assertEquals(backup.saved!!.capture, restored.saved!!.capture)
        assertEquals(backup.saved.table!!.gains, restored.saved.table!!.gains)
        assertEquals(backup.saved.table.confidence, restored.saved.table.confidence)
        assertEquals(backup.saved.table.cellSampleCounts, restored.saved.table.cellSampleCounts)
        assertTrue(restored.draft.table!!.canActivate)
        assertEquals(restored.draft, activationProfile(restored.draft, restored.saved))
        assertEquals(496, restored.saved.table.correctionPercent.size)
        assertFalse(JSONObject(backup.encode()).has("active"))
    }

    @Test fun unfinishedDraftAndSavedOnlyBackupsRoundTrip() {
        val draft = read(CalibrationBackup(profile(true), null).encode())
        assertNull(draft.saved)
        assertTrue(draft.draft!!.table!!.canActivate)
        val saved = read(CalibrationBackup(null, profile()).encode())
        assertNull(saved.draft)
        assertTrue(saved.saved!!.table!!.ready)
    }

    @Test fun invalidFilesCannotBeLoaded() {
        val valid = CalibrationBackup(profile(), profile()).encode()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schema", 99) },
            { it.put("format", "another-app") },
            { it.remove("draft") },
            { it.put("draft", "not a profile") },
            { it.getJSONObject("draft").put("serviceVersion", 1) },
            { it.getJSONObject("draft").put("model", "unknown-model") },
            { it.getJSONObject("draft").put("baseline", JSONArray(List(31) { 1000 })) },
            { it.getJSONObject("draft").getJSONArray("samples").getJSONObject(0).put("rpm", 999) },
            { it.getJSONObject("draft").getJSONObject("table").put("gains", JSONArray(listOf(0.0))) },
            { it.getJSONObject("draft").getJSONObject("table").getJSONArray("confidence").put(0, 2) },
            { it.getJSONObject("draft").put("table", "invalid") },
            { it.getJSONObject("draft").getJSONObject("table").put("manualCoverage", JSONArray(listOf(496))) },
            { it.getJSONObject("saved").put("frameSerial", "another-bike") },
            { it.getJSONObject("saved").put("samples", JSONArray()) },
        )
        mutations.forEachIndexed { i, mutate ->
            val json = JSONObject(valid).also(mutate)
            assertTrue("Malformed backup $i accepted", runCatching { read(json.toString()) }.isFailure)
        }
        assertTrue(runCatching { read("not json") }.isFailure)
        assertTrue(runCatching { read(CalibrationBackup(null, null).encode()) }.isFailure)
        assertTrue(runCatching { ByteArrayInputStream(ByteArray(CalibrationBackup.MAX_BYTES + 1)).use(CalibrationBackup::read) }.isFailure)
    }

    @Test fun onePointSavedBackupUpgradesOldReadinessWithoutChangingEvidence() {
        val original = profile(true).let { it.copy(table = it.table!!.copy(ready = false)) }
        val restored = read(CalibrationBackup(original, original).encode())
        val restoredTable = restored.saved!!.table!!
        assertTrue(restoredTable.canActivate)
        assertEquals(original.capture, restored.saved.capture)
        assertEquals(original.table!!.gains, restoredTable.gains)
        assertEquals(original.table.cellSampleCounts, restoredTable.cellSampleCounts)
    }

    @Test fun manualEditsRoundTripRestartRefitAndRejectStaleSaves() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "manual-manager-${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$id-$name", mode)
        }
        try {
            withContext(Dispatchers.Main) {
                val manager = GrupettoCalibrationManager(isolated)
                val original = profile()
                manager.importBackup(CalibrationBackup(original, null))
                val expected = manager.state.value.displayTable!!
                val edited = CalibrationManualEdit.fillGaps(CalibrationManualEdit.setPercent(expected, setOf(247), -12.5, true))
                manager.saveManualEdit(expected, edited)
                assertFalse(manager.state.value.active)
                val exported = read(manager.exportBackup())
                assertEquals(edited.gains, exported.draft!!.table!!.gains)
                assertEquals(edited.manualCoverage, exported.draft.table!!.manualCoverage)
                assertEquals(original.capture.samples.map { it.pedalWatts },
                    exported.draft.capture.samples.map { it.measuredPedalWatts })
                exported.draft.capture.samples.forEach {
                    assertEquals(edited.corrected(it.nativeWatts, it.ad, it.rpm), it.pedalWatts, 1e-9)
                }
                assertTrue(runCatching { manager.saveManualEdit(expected, edited) }.isFailure)
                val restarted = GrupettoCalibrationManager(isolated)
                val restored = read(restarted.exportBackup()).draft!!.table!!
                restored.gains.indices.forEach { assertEquals(edited.gains[it], restored.gains[it], 1e-12) }
                assertEquals(496, restored.correctionPercent.count { it != null })
                restarted.activate(true)
                withTimeout(5000) { while (!restarted.state.value.active) delay(10) }
                restarted.record(true, null)
                assertTrue(runCatching { restarted.saveManualEdit(restored, edited) }.isFailure)
                val fresh = CalibrationSample(baseline[15].toDouble(), 80.0, 200.0, 240.0,
                    maxOf(System.currentTimeMillis(), restored.recordedThrough + 1))
                restarted.accept(exported.draft.capture.copy(samples = exported.draft.capture.samples + fresh))
                val cell = restored.currentCellIndex(fresh.ad, fresh.rpm)!!
                val weight = (restored.cellSampleCounts[cell] + 1).coerceAtMost(10)
                val previousWatts = restored.corrected(200.0, fresh.ad, fresh.rpm)
                assertEquals(previousWatts + (240.0 - previousWatts) / weight,
                    restarted.state.value.displayTable!!.corrected(200.0, fresh.ad, fresh.rpm), 1e-8)
                assertEquals(weight, restarted.state.value.displayTable!!.cellSampleCounts[cell])
                assertTrue(restarted.state.value.active)
                restarted.record(false, null)
                val active = restarted.state.value.displayTable!!
                val adjusted = CalibrationManualEdit.setPercent(active, setOf(0), 9.0, true)
                restarted.saveManualEdit(active, adjusted)
                assertTrue(restarted.state.value.active)
                assertEquals(adjusted.gains, restarted.state.value.displayTable!!.gains)
                val backup = read(restarted.exportBackup())
                assertEquals(backup.draft!!.table!!.gains, backup.saved!!.table!!.gains)
                val reset = CalibrationManualEdit.resetCorrections(restarted.state.value.displayTable!!)
                restarted.saveManualEdit(restarted.state.value.displayTable!!, reset)
                assertTrue(restarted.state.value.displayTable!!.gains.all { it == 0.0 })
                assertTrue(restarted.state.value.active)
                // Editing an older active snapshot preserves the newer draft's raw evidence
                // while making the edited surface authoritative across restart.
                val sparseDraft = profile(true)
                restarted.importBackup(CalibrationBackup(sparseDraft, original))
                restarted.activate(true)
                withTimeout(5000) { while (!restarted.state.value.active) delay(10) }
                val oldActive = restarted.state.value.displayTable!!
                val rebased = CalibrationManualEdit.setPercent(oldActive, setOf(247), -4.0, true)
                restarted.saveManualEdit(oldActive, rebased)
                val rebasedBackup = read(restarted.exportBackup())
                assertEquals(sparseDraft.capture.samples.map { it.pedalWatts },
                    rebasedBackup.draft!!.capture.samples.map { it.measuredPedalWatts })
                assertEquals(sparseDraft.capture.samples.size, rebasedBackup.draft.table!!.sampleCount)
                val reloaded = read(GrupettoCalibrationManager(isolated).exportBackup()).draft!!.table!!
                rebased.gains.indices.forEach { assertEquals(rebased.gains[it], reloaded.gains[it], 1e-12) }
                // A sparse reset remains usable and must not resurrect the previous edited fallback.
                restarted.importBackup(CalibrationBackup(profile(true), null))
                val sparse = restarted.state.value.displayTable!!
                restarted.saveManualEdit(sparse, CalibrationManualEdit.setPercent(sparse, setOf(0), 5.0, true))
                restarted.activate(true)
                withTimeout(5000) { while (!restarted.state.value.active) delay(10) }
                val sparseActive = restarted.state.value.displayTable!!
                restarted.saveManualEdit(sparseActive, CalibrationManualEdit.resetCorrections(sparseActive))
                assertTrue(restarted.state.value.active)
                assertTrue(restarted.state.value.canActivate)
                assertTrue(restarted.state.value.displayTable!!.gains.all { it == 0.0 })
            }
        } finally {
            directory.deleteRecursively()
            isolated.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun migrationAtomicRecoveryAndClearNeverResurrectLegacyTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "backup-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val draft = profile(true)
            val saved = profile()
            GrupettoCalibrationStore(directory, "grupetto-power-draft.json").save(draft)
            GrupettoCalibrationStore(directory, "grupetto-power-active.json").save(saved)
            val store = CalibrationBackupStore(directory)
            val migrated = store.load()
            assertEquals(draft.capture, migrated.draft!!.capture)
            assertEquals(saved.capture, migrated.saved!!.capture)
            store.save(migrated)
            val file = File(directory, "grupetto-calibration.json")
            assertTrue(file.renameTo(File(file.path + ".bak")))
            assertEquals(saved.capture, store.load().saved!!.capture)
            store.save(CalibrationBackup(null, null))
            assertFalse(store.load().hasData)
            assertTrue(File(directory, "grupetto-power-active.json").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun importingDeactivatesCorrectionPersistsBothTablesAndRejectsRecording() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "backup-manager-${System.nanoTime()}"
        val directory = File(context.cacheDir, id).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$id-$name", mode)
        }
        try {
            withContext(Dispatchers.Main) {
                val manager = GrupettoCalibrationManager(isolated)
                val original = CalibrationBackup(profile(), profile())
                manager.importBackup(original)
                manager.activate(true)
                withTimeout(5000) { while (!manager.state.value.active) delay(10) }
                val replacement = CalibrationBackup(profile(true), profile())
                manager.importBackup(replacement)
                assertFalse(manager.state.value.active)
                assertFalse(manager.state.value.recording)
                assertFalse(manager.state.value.verified)
                assertEquals(1, manager.state.value.count)
                assertEquals(replacement.saved!!.capture.samples.size, manager.state.value.savedCount)
                val exported = read(manager.exportBackup())
                assertEquals(replacement.draft!!.capture, exported.draft!!.capture)
                assertEquals(replacement.saved.capture, exported.saved!!.capture)
                assertFalse(isolated.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE).getBoolean("active", true))
                assertEquals(replacement.draft.capture, CalibrationBackupStore(directory).load().draft!!.capture)
                manager.record(true, null)
                assertTrue(runCatching { manager.importBackup(original) }.isFailure)
                manager.record(false, null)
                manager.verifyBaseline(baseline.map { it + 1 })
                assertTrue(runCatching { manager.importBackup(original) }.exceptionOrNull() is CalibrationBaselineMismatch)
                assertEquals(1, manager.state.value.count)
            }
        } finally {
            directory.deleteRecursively()
            isolated.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
