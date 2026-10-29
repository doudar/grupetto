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

    @Test fun backupPreservesSparseDraftAndDistinctUsableSnapshot() {
        val backup = CalibrationBackup(profile(true), profile())
        val restored = read(backup.encode())
        assertEquals(backup.draft!!.capture, restored.draft!!.capture)
        assertEquals(backup.saved!!.capture, restored.saved!!.capture)
        assertEquals(backup.saved.table!!.gains, restored.saved.table!!.gains)
        assertEquals(backup.saved.table.confidence, restored.saved.table.confidence)
        assertFalse(restored.draft.table!!.ready)
        assertEquals(restored.saved, activationProfile(restored.draft, restored.saved))
        assertEquals(496, restored.saved.table.correctionPercent.size)
        assertFalse(JSONObject(backup.encode()).has("active"))
    }

    @Test fun unfinishedDraftAndSavedOnlyBackupsRoundTrip() {
        val draft = read(CalibrationBackup(profile(true), null).encode())
        assertNull(draft.saved)
        assertFalse(draft.draft!!.table!!.ready)
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
            { it.getJSONObject("saved").put("frameSerial", "another-bike") },
            { it.getJSONObject("saved").getJSONObject("table").put("ready", false) },
        )
        mutations.forEachIndexed { i, mutate ->
            val json = JSONObject(valid).also(mutate)
            assertTrue("Malformed backup $i accepted", runCatching { read(json.toString()) }.isFailure)
        }
        assertTrue(runCatching { read("not json") }.isFailure)
        assertTrue(runCatching { read(CalibrationBackup(null, null).encode()) }.isFailure)
        assertTrue(runCatching { ByteArrayInputStream(ByteArray(CalibrationBackup.MAX_BYTES + 1)).use(CalibrationBackup::read) }.isFailure)
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
