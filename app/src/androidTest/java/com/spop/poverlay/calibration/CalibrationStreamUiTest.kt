package com.spop.poverlay.calibration

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import android.graphics.Bitmap

class CalibrationStreamUiTest {
    @get:Rule val compose = createComposeRule()
    private val baseline = List(31) { 1000 + it * 100 }
    private val sample = CalibrationSample(2000.0, 80.0, 200.0, 220.0, 10000)
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "stream-${System.nanoTime()}").apply { mkdirs() }
    private fun profile(points: List<CalibrationSample> = listOf(sample)) =
        updateUnifiedCalibration(CalibrationCapture("stream-bike", "meter", 2040490L, baseline, points), null)

    @Test fun schemaThreeMigrationPreservesExactLegacyEvidenceGainsAndRecoveryCopy() {
        val directory = directory()
        try {
            val original = profile()
            val old = JSONObject(CalibrationBackup(original, original).encode())
            for (key in listOf("draft", "saved")) old.getJSONObject(key).apply {
                put("schema", 3); put("model", "direct_cell_gain_v3")
                getJSONArray("samples").getJSONObject(0).remove("kind")
            }
            val file = File(directory, "grupetto-calibration.json")
            val bytes = old.toString().toByteArray()
            file.writeBytes(bytes)
            val migrated = CalibrationBackupStore(directory).load()
            assertEquals(original.capture.samples, migrated.draft!!.capture.samples)
            assertEquals(original.table!!.gains, migrated.draft.table!!.gains)
            assertEquals(0, migrated.draft.table.streamSeconds)
            assertEquals(1, migrated.draft.table.legacyEffortCount)
            assertEquals(4, JSONObject(file.readText()).getJSONObject("draft").getInt("schema"))
            val recovery = directory.listFiles()!!.single { it.name.contains("before-stream-v4") }
            assertArrayEquals(bytes, recovery.readBytes())
            CalibrationBackupStore(directory).load()
            assertEquals(1, directory.listFiles()!!.count { it.name.contains("before-stream-v4") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun streamBackupRoundTripsOriginalMeasurementsAndRejectsInventedDurationOrOversizedBucket() {
        val points = (0L until 100).map { sample.copy(capturedAt = 10000 + it * 1000,
            observation = CalibrationObservation(it * 1000, it * 1000)) }
        val source = profile(points)
        val encoded = CalibrationBackup(source, source).encode()
        val restored = CalibrationBackup.decode(encoded)
        assertEquals(points, restored.draft!!.capture.samples)
        assertEquals(source.table!!.gains, restored.draft.table!!.gains)
        val invalid = JSONObject(encoded)
        invalid.getJSONObject("draft").getJSONArray("samples").getJSONArray(0).put(9, 100000)
        assertThrows(IllegalArgumentException::class.java) { CalibrationBackup.decode(invalid.toString()) }
        val oversized = JSONObject(encoded)
        val array = oversized.getJSONObject("draft").getJSONArray("samples")
        array.put(org.json.JSONArray(array.getJSONArray(0).toString()).put(5, 999999))
        assertThrows(IllegalArgumentException::class.java) { CalibrationBackup.decode(oversized.toString()) }
    }

    @Test fun rawCaptureUpgradeKeepsOriginalAggregateAndDistinctStreamProvenance() {
        val directory = directory()
        try {
            val capture = profile().capture
            val file = File(directory, "bike-power-calibration.json")
            val old = JSONObject().put("schema", 1).put("frameSerial", capture.frameSerial)
                .put("meterAddress", capture.meterAddress).put("serviceVersion", capture.serviceVersion)
                .put("baseline", org.json.JSONArray(baseline)).put("samples", org.json.JSONArray().put(
                    JSONObject().put("ad", sample.ad).put("rpm", sample.rpm).put("nativeWatts", sample.nativeWatts)
                        .put("pedalWatts", sample.pedalWatts).put("capturedAt", sample.capturedAt)))
            val bytes = old.toString().toByteArray()
            file.writeBytes(bytes)
            val store = CalibrationStore(directory)
            assertEquals(capture, store.load())
            val point = sample.copy(capturedAt = 11000, observation = CalibrationObservation(1000, 1000))
            val next = capture.copy(samples = capture.samples + point)
            store.save(next, CalibrationFit(baseline, emptyList()))
            assertEquals(next, CalibrationStore(directory).load())
            assertArrayEquals(bytes, directory.listFiles()!!.single { it.name.contains("before-stream-v4") }.readBytes())
            assertEquals(2, JSONObject(file.readText()).getInt("schema"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun recordPauseRestartAndManualEditKeepOneSharedStreamTable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = directory()
        val id = directory.name
        val fixture = object : ContextWrapper(context) {
            override fun getFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences("$id-$name", mode)
        }
        val manager = withContext(Dispatchers.Main) { GrupettoCalibrationManager(fixture) }
        try {
            withContext(Dispatchers.Main) { manager.record(true, null) }
            val point = sample.copy(capturedAt = System.currentTimeMillis() + 1000,
                observation = CalibrationObservation(1000, 1000))
            withContext(Dispatchers.Main) {
                manager.accept(profile(listOf(point)).capture)
                manager.record(false, null)
            }
            assertEquals(1, manager.state.value.count)
            assertTrue(manager.state.value.canActivate)
            val expected = manager.state.value.table!!
            val edited = CalibrationManualEdit.setPercent(expected, setOf(10 * 16 + 7), 20.0, false)
            withContext(Dispatchers.Main) { manager.saveManualEdit(expected, edited) }
            val saved = CalibrationBackup.decode(manager.exportBackup())
            assertEquals(220.0, saved.draft!!.capture.samples.single().measuredPedalWatts, 0.0)
            assertEquals(point.observation, saved.draft.capture.samples.single().observation)
            val restarted = withContext(Dispatchers.Main) { GrupettoCalibrationManager(fixture) }
            val reloaded = CalibrationBackup.decode(restarted.exportBackup())
            assertFalse(restarted.state.value.recording)
            assertEquals(saved.draft.table!!.gains, reloaded.draft!!.table!!.gains)
            withContext(Dispatchers.Main) { restarted.record(true, reloaded.draft.capture); restarted.record(false, null) }
            assertEquals(saved.draft.table.gains, restarted.state.value.table!!.gains)
        } finally {
            withContext(Dispatchers.Main) { manager.record(false, null) }
            directory.deleteRecursively()
            fixture.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun maximumCapacityDualProfileBackupRemainsBoundedAndReadable() {
        val points = (0 until 496).flatMap { cell -> (0 until 100).map { second ->
            val time = (cell * 100L + second) * 1000
            sample.copy(ad = baseline[cell / 16].toDouble(), rpm = 45.0 + cell % 16 * 5,
                capturedAt = 1000000 + time, observation = CalibrationObservation(time, time))
        } }
        val full = updateUnifiedCalibration(CalibrationCapture("R00000000000000", "meter", 2040490L,
            baseline, points), null) // Exercise reference normalization at maximum capacity too.
        val text = CalibrationBackup(full, full).encode()
        assertTrue(text.toByteArray().size < CalibrationBackup.MAX_BYTES)
        val decoded = CalibrationBackup.decode(text)
        assertEquals(49600, decoded.draft!!.capture.samples.size)
        assertEquals(49600, decoded.saved!!.capture.samples.size)
        assertEquals(496, decoded.draft.table!!.cells)
        assertEquals(points.first(), decoded.draft.capture.samples.first())
        assertEquals(points.last(), decoded.saved.capture.samples.last())
    }

    @Test fun fullStreamingTableKeepsBottomBucketAndActionsVisible() {
        val capture = CalibrationUiState(enabled = true, baseline = baseline, pedalWatts = 220,
            frame = CalibrationFrame(0, 2000, 80.0, 200.0, "bike", 1),
            progress = CaptureProgress(CapturePhase.Collecting, streaming = true, cell = 167, bucketSeconds = 63))
        val table = profile(listOf(sample.copy(observation = CalibrationObservation(0, 0)))).table!!
        compose.setContent { MaterialTheme {
            com.spop.poverlay.SettingsDialog("Calibrate using power pedals", {}, compact = true) {
                GrupettoCalibrationPanel(GrupettoCalibrationUiState(recording = true, count = 123,
                    table = table), {}, {}, {}, {}, meterReady = true, capture = capture,
                    onManualEdit = {}, onCaptureSettings = {}, onRefit = {})
            }
        } }
        compose.onNodeWithContentDescription("Cell 11, 80 rpm: 63 of 100 accepted seconds. Collecting paired observations").assertIsDisplayed()
        val bucket = compose.onNodeWithContentDescription("Cell 11, 80 rpm: 63 of 100 accepted seconds. Collecting paired observations").fetchSemanticsNode().boundsInRoot
        val legend = compose.onNodeWithText("gray: no saved cell data", substring = true).fetchSemanticsNode().boundsInRoot
        val errors = compose.onNodeWithText("Average error vs pedals", substring = true).fetchSemanticsNode().boundsInRoot
        assertTrue(bucket.top >= legend.bottom)
        assertTrue(bucket.bottom <= errors.top)
        listOf("Record", "Activate", "Capture settings", "Guided capture", "Calculate New Solution", "Done")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir, "stream-bucket-layout.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun cadenceNormalizationNeverReplacesDisplayedPedalWatts() {
        val original = sample.copy(ad = 3000.0, rpm = 97.0, nativeWatts = 500.0, pedalWatts = 550.0,
            observation = CalibrationObservation(0, 0))
        val reference = PelotonCadenceReference(baseline, PelotonCadenceReference.Model.Rexon)
        val normalized = reference.normalize(original)!!
        assertTrue(normalized.pedalWatts < 550.0)
        val table = GrupettoCalibrationFitter.fit(baseline, listOf(original), cadenceReference = reference)!!
        compose.setContent { MaterialTheme {
            GrupettoCalibrationPanel(GrupettoCalibrationUiState(count = 1, table = table), {}, {}, {}, {},
                meterReady = true, metrics = CalibrationLiveMetrics(bikeWatts = 500.0, pedalWatts = original.pedalWatts,
                    rpm = original.rpm, resistance = 65.0, correctedWatts = 550.0))
        } }
        compose.onAllNodesWithText("550").assertCountEquals(2) // Raw pedals and correctly calibrated bike.
        compose.onNodeWithText(normalized.pedalWatts.toInt().toString()).assertDoesNotExist()
        assertEquals(550.0, original.pedalWatts, 0.0)
    }

    @Test fun bucketReportsTrueCellSecondsDuringFillCrossfadeOutlierWaitingAndPause() {
        val state = mutableStateOf(CalibrationUiState(enabled = true, frame = CalibrationFrame(0, 2000, 80.0,
            200.0, "bike", 1), progress = CaptureProgress(CapturePhase.Collecting,
            streaming = true, cell = 167, bucketSeconds = 25)))
        compose.setContent { MaterialTheme { CalibrationStreamBucket(state.value, true) } }
        compose.onNodeWithContentDescription("Cell 11, 80 rpm: 25 of 100 accepted seconds. Collecting paired observations").assertExists()
        compose.runOnIdle { state.value = state.value.copy(progress = state.value.progress.copy(cell = 183,
            bucketSeconds = 80, phase = CapturePhase.Outlier)) }
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithContentDescription("Cell 12, 80 rpm: 80 of 100 accepted seconds. Checking a possible outlier").assertExists()
        compose.runOnIdle { state.value = state.value.copy(enabled = false) }
        compose.onNodeWithContentDescription("Cell 12, 80 rpm: 80 of 100 accepted seconds. Paused").assertExists()
        compose.runOnIdle { state.value = state.value.copy(enabled = true, frame = null) }
        compose.onNodeWithContentDescription("Cell 12, 80 rpm: 80 of 100 accepted seconds. Waiting for bike + pedals").assertExists()
    }
}
