package com.spop.poverlay.calibration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.math.ln

/** Schema changes must preserve the exact saved surface, not reconstruct an older fitted layer. */
class CalibrationPersistenceMigrationTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private val samples = listOf(
        CalibrationSample(2000.0, 80.0, 200.0, 225.0, 1000L),
        CalibrationSample(2250.0, 87.5, 300.0, 315.0, 2000L),
        CalibrationSample(2500.0, 95.0, 400.0, 410.0, 3000L),
    )

    private fun profile(): GrupettoCalibrationProfile {
        val table = GrupettoCalibrationTable(baseline,
            List(496) { if (it == 10 * 16 + 7) ln(1.3) else ln(1.05) },
            List(496) { .7 }, samples.size, 3, 15.0, 4.0, true,
            recordedThrough = samples.maxOf { it.capturedAt }).withCalibrationCoverage(samples)
        return GrupettoCalibrationProfile(CalibrationCapture("bike", "meter", 2040490L, baseline, samples), table)
    }

    private fun legacy(profile: GrupettoCalibrationProfile, offsets: List<Double>? = null): JSONObject =
        GrupettoCalibrationStore.encode(profile).apply {
            put("schema", 1)
            put("model", "regularized_gaussian_log_gain_v1")
            getJSONObject("table").apply {
                remove("recordedThrough")
                if (offsets != null) put("manualOffsets", JSONArray(offsets))
            }
        }

    private fun backup(schema: Int, draft: JSONObject?, saved: JSONObject?): String = JSONObject()
        .put("format", "grupetto-calibration-backup").put("schema", schema)
        .put("draft", draft ?: JSONObject.NULL).put("saved", saved ?: JSONObject.NULL).toString()

    @Test fun schemaTwoRoundTripPreservesUnifiedGainsTargetsAndOriginalMeasurements() {
        val original = profile()
        var edited = CalibrationManualEdit.setPercent(original.table!!, setOf(167), -15.0, true)
        repeat(4) { edited = CalibrationManualEdit.smooth(edited, (0 until 496).toSet()) }
        val updated = samplesForCalibrationTable(samples, edited)
        val unified = original.copy(capture = original.capture.copy(samples = updated), table = edited)
        val encoded = GrupettoCalibrationStore.encode(unified)
        assertEquals(2, encoded.getInt("schema"))
        assertEquals("unified_gaussian_log_gain_v2", encoded.getString("model"))
        assertFalse(encoded.getJSONObject("table").has("manualOffsets"))
        assertEquals(edited.recordedThrough, encoded.getJSONObject("table").getLong("recordedThrough"))
        val restored = GrupettoCalibrationStore.decode(JSONObject(encoded.toString()))
        assertEquals(unified.capture, restored.capture)
        assertEquals(edited.gains, restored.table!!.gains)
        assertEquals(edited.confidence, restored.table.confidence)
        assertEquals(edited.manualCoverage, restored.table.manualCoverage)
        assertEquals(edited.recordedThrough, restored.table.recordedThrough)
        assertEquals(original.table.recordedCoverage, restored.table.recordedCoverage)
        val editedAgain = CalibrationManualEdit.soften(restored.table, (0 until 496).toSet())
        val updatedAgain = samplesForCalibrationTable(restored.capture.samples, editedAgain)
        updatedAgain.zip(samples).forEach { (updatedSample, recorded) ->
            assertEquals(recorded.pedalWatts, updatedSample.recordedPedalWatts!!, 0.0)
            assertEquals(recorded.capturedAt, updatedSample.capturedAt)
        }
        val second = restored.copy(capture = restored.capture.copy(samples = updatedAgain), table = editedAgain)
        assertEquals(second.capture, GrupettoCalibrationStore.decode(GrupettoCalibrationStore.encode(second)).capture)
    }

    @Test fun legacyRepeatedSmoothingFoldsTargetsOnceAndKeepsExactSavedSurface() {
        val original = profile()
        var smoothed = original.table!!
        repeat(7) { smoothed = CalibrationManualEdit.smooth(smoothed, (0 until 496).toSet()) }
        val edited = original.copy(table = smoothed)
        val offsets = smoothed.gains.indices.map { smoothed.gains[it] - original.table.gains[it] }
        val beforeLoad = System.currentTimeMillis()
        val migrated = GrupettoCalibrationStore.decode(legacy(edited, offsets))
        assertEquals(smoothed.gains, migrated.table!!.gains)
        assertEquals(smoothed.confidence, migrated.table.confidence)
        assertEquals(smoothed.manualCoverage, migrated.table.manualCoverage)
        assertEquals(smoothed.cells, migrated.table.cells)
        assertEquals(smoothed.beforeRmse, migrated.table.beforeRmse, 0.0)
        assertEquals(smoothed.afterRmse, migrated.table.afterRmse, 0.0)
        assertEquals(smoothed.ready, migrated.table.ready)
        assertTrue(migrated.table.recordedThrough >= beforeLoad)
        assertTrue(migrated.table.recordedThrough >= samples.maxOf { it.capturedAt })
        assertEquals(samples.size, migrated.capture.samples.size)
        migrated.capture.samples.zip(samples).forEach { (updated, recorded) ->
            assertEquals(smoothed.corrected(recorded.nativeWatts, recorded.ad, recorded.rpm), updated.pedalWatts, 0.0)
            assertEquals(recorded.pedalWatts, updated.recordedPedalWatts!!, 0.0)
            assertEquals(recorded.copy(pedalWatts = updated.pedalWatts, recordedPedalWatts = recorded.pedalWatts), updated)
        }
        val secondLoad = GrupettoCalibrationStore.decode(GrupettoCalibrationStore.encode(migrated))
        assertEquals(migrated, secondLoad)
    }

    @Test fun legacyWithoutManualOffsetsDoesNotRewriteRecordedObservations() {
        val original = profile()
        listOf<List<Double>?>(null, emptyList()).forEach { offsets ->
            val restored = GrupettoCalibrationStore.decode(legacy(original, offsets))
            assertEquals(original.capture, restored.capture)
            assertEquals(original.table!!.gains, restored.table!!.gains)
            assertTrue(restored.capture.samples.all { it.recordedPedalWatts == null })
            assertTrue(restored.table.recordedThrough >= samples.maxOf { it.capturedAt })
        }
    }

    @Test fun legacyNoOpManualLayerDoesNotInventOriginalsOrObservations() {
        val original = profile()
        val unchanged = samples.map { it.copy(pedalWatts = it.nativeWatts) }
        val neutral = original.copy(capture = original.capture.copy(samples = unchanged),
            table = original.table!!.copy(gains = List(496) { 0.0 }, manualCoverage = setOf(167)))
        val migrated = GrupettoCalibrationStore.decode(legacy(neutral, List(496) { 0.0 }))
        assertEquals(unchanged, migrated.capture.samples)
        assertEquals(neutral.table!!.manualCoverage, migrated.table!!.manualCoverage)
    }

    @Test fun bothBackupWrappersLoadAndNewBackupsRejectOlderSchemaAssumptions() {
        val original = profile()
        val old = legacy(original)
        listOf(1, 2).forEach { wrapperSchema ->
            val restored = ByteArrayInputStream(backup(wrapperSchema, old, old).toByteArray())
                .use(CalibrationBackup::read)
            assertEquals(original.capture, restored.draft!!.capture)
            assertEquals(original.table!!.gains, restored.saved!!.table!!.gains)
            val encoded = JSONObject(restored.encode())
            assertEquals(2, encoded.getInt("schema"))
            assertEquals(2, encoded.getJSONObject("draft").getInt("schema"))
            assertEquals(2, encoded.getJSONObject("saved").getInt("schema"))
            assertFalse(encoded.getJSONObject("saved").getJSONObject("table").has("manualOffsets"))
            assertEquals(restored, CalibrationBackup.decode(encoded.toString()))
        }
        val draftOnly = CalibrationBackup.decode(backup(1, old, null))
        assertNull(draftOnly.saved)
        assertEquals(original.capture, draftOnly.draft!!.capture)
        val savedOnly = CalibrationBackup.decode(backup(1, null, old))
        assertNull(savedOnly.draft)
        assertEquals(original.capture, savedOnly.saved!!.capture)
    }

    @Test fun boundedEditedTargetsOutsideRawPowerRangeRoundTripWithTheirOriginals() {
        val original = profile()
        val editedSamples = listOf(
            CalibrationSample(2000.0, 80.0, 5.0, 3.25, 1000L, recordedPedalWatts = 5.0),
            CalibrationSample(2500.0, 95.0, 1500.0, 2025.0, 2000L, recordedPedalWatts = 1490.0),
        )
        val edited = original.copy(capture = original.capture.copy(samples = editedSamples))
        assertEquals(editedSamples, GrupettoCalibrationStore.decode(GrupettoCalibrationStore.encode(edited)).capture.samples)
    }

    @Test fun rejectedNewerObservationSurvivesRestartWithoutBlockingNextValidRecording() {
        val original = profile()
        val recorded = samples.first().copy(pedalWatts = 220.0, capturedAt = 10L)
        val prior = original.copy(capture = original.capture.copy(samples = listOf(recorded)),
            table = original.table!!.copy(gains = List(496) { ln(1.1) }, sampleCount = 1,
                recordedThrough = 10L).withCalibrationCoverage(listOf(recorded)))
        val rejected = recorded.copy(pedalWatts = 450.0, capturedAt = 999L)
        assertFalse(rejected.usableForCalibration(baseline))
        val capture = prior.capture.copy(samples = listOf(recorded, rejected))
        val unchanged = updateUnifiedCalibration(capture, prior)
        val restored = GrupettoCalibrationStore.decode(GrupettoCalibrationStore.encode(unchanged))
        assertEquals(prior.table!!.gains, restored.table!!.gains)
        assertEquals(10L, restored.table.recordedThrough)
        assertEquals(capture.samples, restored.capture.samples)

        // A rejected future timestamp must not make a later valid arrival look already consumed.
        val next = recorded.copy(pedalWatts = 180.0, capturedAt = 11L)
        val nextCapture = restored.capture.copy(samples = restored.capture.samples + next)
        val updated = updateUnifiedCalibration(nextCapture, restored)
        assertEquals(180.0, updated.table!!.corrected(next.nativeWatts, next.ad, next.rpm), 1e-8)
        assertEquals(11L, updated.table.recordedThrough)
        val restarted = GrupettoCalibrationStore.decode(GrupettoCalibrationStore.encode(updated))
        assertEquals(updated.table.gains, restarted.table!!.gains)
        assertEquals(11L, restarted.table.recordedThrough)
        assertEquals(nextCapture.samples, restarted.capture.samples)
    }

    @Test fun malformedLegacyLayersAndNewAuditFieldsAreRejected() {
        val original = profile()
        val legacyMutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("table").put("manualOffsets", JSONArray(listOf(0.0))) },
            { it.getJSONObject("table").put("manualOffsets", JSONArray(List(496) { 9.0 })) },
            { it.getJSONObject("table").put("manualOffsets", JSONArray(List(496) { "NaN" })) },
            { it.getJSONObject("table").put("manualCoverage", JSONArray(listOf(496))) },
            { it.getJSONObject("table").remove("manualOffsets") },
            { it.put("model", "unified_gaussian_log_gain_v2") },
            { it.getJSONArray("samples").getJSONObject(0).put("pedalWatts", 3.25) },
        )
        val legacyProfile = original.copy(table = original.table!!.copy(manualCoverage = setOf(167)))
        legacyMutations.forEachIndexed { index, mutate ->
            val invalid = legacy(legacyProfile, List(496) { 0.0 }).also(mutate)
            assertTrue("Malformed legacy profile $index accepted", runCatching { GrupettoCalibrationStore.decode(invalid) }.isFailure)
        }
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("table").put("manualOffsets", JSONArray()) },
            { it.getJSONObject("table").put("recordedThrough", 2999L) },
            { it.getJSONObject("table").remove("recordedThrough") },
            { it.put("model", "regularized_gaussian_log_gain_v1") },
            { it.getJSONArray("samples").getJSONObject(0).put("recordedPedalWatts", 1501.0) },
            { it.getJSONArray("samples").getJSONObject(0).put("recordedPedalWatts", 4.0) },
            { it.getJSONArray("samples").getJSONObject(0).put("recordedPedalWatts", "NaN") },
            { it.getJSONArray("samples").getJSONObject(0).put("recordedPedalWatts", 225.0).put("pedalWatts", 280.0) },
            { it.getJSONArray("samples").getJSONObject(0).put("recordedPedalWatts", 225.0).put("pedalWatts", "NaN") },
        )
        mutations.forEachIndexed { index, mutate ->
            val invalid = GrupettoCalibrationStore.encode(original).also(mutate)
            assertTrue("Malformed unified profile $index accepted", runCatching { GrupettoCalibrationStore.decode(invalid) }.isFailure)
        }
    }
}
