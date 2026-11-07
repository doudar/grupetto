package com.spop.poverlay.calibration

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*

class CalibrationTemperaturePersistenceTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val temperature = CalibrationTemperature(100.0, 65.0, 1000,
        confidence = TemperatureConfidence.InterruptedHistory)
    private val sample = CalibrationSample(2000.0, 95.0, 200.0, 289.0, 1000,
        observation = CalibrationObservation(1000, 1000), temperature = temperature)
    private fun profile(point: CalibrationSample = sample) = updateUnifiedCalibration(
        CalibrationCapture("thermal-bike", "pedals", 2040490, baseline, listOf(point)), null)
    private fun directory() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "thermal-persistence-${System.nanoTime()}").apply { mkdirs() }

    @Test fun temperatureRawMeasurementsConfidenceAndReferenceGainsRoundTrip() {
        val p = profile()
        val text = CalibrationBackup(p, p).encode()
        val json = JSONObject(text)
        assertEquals(3, json.getInt("schema"))
        assertEquals(5, json.getJSONObject("draft").getInt("schema"))
        val restored = CalibrationBackup.decode(text)
        assertEquals(p, restored.draft)
        assertEquals(sample, restored.draft!!.capture.samples.single())
        val table = restored.saved!!.table!!
        assertEquals(289.0, table.correctedSample(sample), 1e-9)
        assertTrue(kotlin.math.exp(table.gains[table.thermalCells.single()]) > 1.5)
    }

    @Test fun legacyHistoryMetadataRoundTripsWithoutAFormatChange() {
        TemperatureConfidence.values().forEach { confidence ->
            listOf(false, true).forEach { unobserved ->
                val old = sample.copy(temperature = temperature.copy(
                    historyUnobserved = unobserved, confidence = confidence))
                val p = profile(old)
                val encoded = CalibrationBackup(p, p).encode()
                val json = JSONObject(encoded)
                assertEquals(3, json.getInt("schema"))
                assertEquals(5, json.getJSONObject("draft").getInt("schema"))
                assertEquals(6, json.getJSONObject("draft").getJSONArray("samples")
                    .getJSONArray(0).getJSONArray(11).length())
                assertEquals(old, CalibrationBackup.decode(encoded).draft!!.capture.samples.single())
            }
        }
    }

    @Test fun schemaFourUpgradeKeepsUnknownTemperatureAndExactOldSurfaceWithRecoveryCopy() {
        val directory = directory()
        try {
            val p = profile(sample.copy(temperature = null))
            val old = JSONObject(CalibrationBackup(p, p).encode()).put("schema", 2)
            listOf("draft", "saved").forEach { key -> old.getJSONObject(key).apply {
                put("schema", 4); put("model", "stable_cell_stream_v4"); remove("temperatureCorrectionModel")
                getJSONObject("table").remove("thermalAnchors")
                getJSONArray("samples").getJSONArray(0).remove(11)
            } }
            val file = File(directory, "grupetto-calibration.json")
            val original = old.toString().toByteArray()
            file.writeBytes(original)
            val restored = CalibrationBackupStore(directory).load()
            assertEquals(p.table!!.gains, restored.saved!!.table!!.gains)
            assertTrue(restored.saved.table!!.thermalCells.isEmpty())
            assertNull(restored.saved.capture.samples.single().temperature)
            assertEquals(p.capture.samples, restored.saved.capture.samples)
            assertArrayEquals(original, directory.listFiles()!!.single { it.name.contains("before-thermal-v5") }.readBytes())
            assertEquals(restored, CalibrationBackupStore(directory).load())
            assertEquals(1, directory.listFiles()!!.count { it.name.contains("before-thermal-v5") })
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidTemperatureProvenanceOrUnknownCorrectionModelIsRejected() {
        val p = profile()
        val valid = CalibrationBackup(p, p).encode()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("temperatureCorrectionModel", "another-alpha") },
            { it.getJSONArray("samples").getJSONArray(0).getJSONArray(11).put(2, 1001) },
            { it.getJSONArray("samples").getJSONArray(0).getJSONArray(11).put(3, 99) },
            { it.getJSONArray("samples").getJSONArray(0).getJSONArray(11).put(5, "measured") },
            { it.getJSONObject("table").getJSONArray("thermalAnchors").put(170, 2.0) })
        mutations.forEach { mutate ->
            val json = JSONObject(valid)
            mutate(json.getJSONObject("draft"))
            assertTrue(runCatching { CalibrationBackup.decode(json.toString()) }.isFailure)
        }
    }

    @Test fun rawCaptureStoreKeepsOriginalTemperatureWithoutNormalizingWatts() {
        val directory = directory()
        try {
            val capture = profile().capture
            val store = CalibrationStore(directory)
            store.save(capture, CalibrationFit(baseline, emptyList()))
            assertEquals(capture, store.load())
            assertEquals(200.0, store.load()!!.samples.single().nativeWatts, 0.0)
            assertEquals(289.0, store.load()!!.samples.single().pedalWatts, 0.0)
            assertEquals(3, JSONObject(File(directory, "bike-power-calibration.json").readText()).getInt("schema"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun delayedCaptureUsesPastEstimateWhileOutputCanHoldLastAppliedTemperature() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val tracker = FlywheelTemperatureTracker(context, scope)
        scope.cancel() // Deterministic fixture: no asynchronous ticker or checkpoint writes.
        val now = android.os.SystemClock.elapsedRealtime()
        tracker.observe(CalibrationFrame(now, 2000, 95.0, 200.0, "bike", 1))
        val first = tracker.temperatureAt(now)!!
        // Runtime history checks are gone; new samples retain a conservative legacy marker.
        assertTrue(first.historyUnobserved)
        assertEquals(TemperatureConfidence.InterruptedHistory, first.confidence)
        tracker.observe(CalibrationFrame(now + 1000, 2000, 95.0, 200.0, "bike", 1))
        assertEquals(first, tracker.temperatureAt(now))
        assertNull(tracker.temperatureAt(now - 1))
        val lastApplied = tracker.outputTemperatureAt(now + 1000)!!
        assertNull(tracker.temperatureAt(now + 5000))
        assertEquals(lastApplied, tracker.outputTemperatureAt(now + 5000))
        assertEquals(now + 1000, lastApplied.estimatedAt)
    }

    @Test fun fullThermalBucketsFitBackupBoundAndReloadOnTabletHeap() {
        val directory = directory()
        try {
            val points = (0 until 496).flatMap { cell -> (0 until 100).map { second ->
                val at = (cell * 100L + second + 1) * 1000
                sample.copy(ad = baseline[cell / 16].toDouble(), rpm = 45.0 + cell % 16 * 5,
                    capturedAt = at, observation = CalibrationObservation(at, at),
                    temperature = temperature.copy(estimatedAt = at))
            } }
            val capture = CalibrationCapture("thermal-bike", "pedals", 2040490, baseline, points)
            val p = updateUnifiedCalibration(capture, null)
            val backup = CalibrationBackup(p, p)
            val encoded = backup.encode()
            assertTrue(encoded.toByteArray().size < CalibrationBackup.MAX_BYTES)
            CalibrationBackupStore(directory).save(backup)
            val restored = CalibrationBackupStore(directory).load()
            assertEquals(49600, restored.saved!!.capture.samples.size)
            assertEquals(496, restored.saved.table!!.thermalCells.size)
            assertEquals(points.last().temperature, restored.saved.capture.samples.last().temperature)
            val raw = CalibrationStore(directory)
            raw.save(capture, CalibrationFit(baseline, emptyList()))
            assertEquals(49600, raw.load()!!.samples.size)
        } finally { directory.deleteRecursively() }
    }
}
