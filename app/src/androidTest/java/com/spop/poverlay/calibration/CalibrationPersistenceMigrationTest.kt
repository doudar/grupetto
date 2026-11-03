package com.spop.poverlay.calibration

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
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
            recordedThrough = samples.maxOf { it.capturedAt }, cellSampleCounts = List(496) { 1 }).withCalibrationCoverage(samples)
        return GrupettoCalibrationProfile(CalibrationCapture("bike", "meter", 2040490L, baseline, samples), table)
    }

    private fun legacy(profile: GrupettoCalibrationProfile, offsets: List<Double>? = null): JSONObject =
        GrupettoCalibrationStore.encode(profile).apply {
            put("schema", 1)
            put("model", "regularized_gaussian_log_gain_v1")
            getJSONObject("table").apply {
                remove("recordedThrough")
                remove("cellSampleCounts")
                put("cells", 3) // Legacy cells counted coarse observation buckets, not populated grid cells.
                if (offsets != null) put("manualOffsets", JSONArray(offsets))
            }
        }

    private fun backup(schema: Int, draft: JSONObject?, saved: JSONObject?): String = JSONObject()
        .put("format", "grupetto-calibration-backup").put("schema", schema)
        .put("draft", draft ?: JSONObject.NULL).put("saved", saved ?: JSONObject.NULL).toString()

    @Test fun schemaThreeRoundTripPreservesUnifiedGainsTargetsAndOriginalMeasurements() {
        val original = profile()
        var edited = CalibrationManualEdit.setPercent(original.table!!, setOf(167), -15.0, true)
        repeat(4) { edited = CalibrationManualEdit.smooth(edited, (0 until 496).toSet()) }
        val updated = samplesForCalibrationTable(samples, edited)
        val unified = original.copy(capture = original.capture.copy(samples = updated), table = edited)
        val encoded = GrupettoCalibrationStore.encode(unified)
        assertEquals(3, encoded.getInt("schema"))
        assertEquals("direct_cell_gain_v3", encoded.getString("model"))
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
        assertEquals(496, migrated.table.cells)
        assertEquals(smoothed.beforeRmse, migrated.table.beforeRmse, 0.0)
        assertEquals(smoothed.afterRmse, migrated.table.afterRmse, 0.0)
        assertEquals(smoothed.ready, migrated.table.ready)
        assertTrue(migrated.table.recordedThrough >= beforeLoad)
        assertTrue(migrated.table.recordedThrough >= samples.maxOf { it.capturedAt })
        assertEquals(samples.size, migrated.capture.samples.size)
        migrated.capture.samples.zip(samples).forEach { (updated, recorded) ->
            assertEquals(legacyCorrected(smoothed, recorded), updated.pedalWatts, 0.0)
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
            assertEquals(3, encoded.getJSONObject("draft").getInt("schema"))
            assertEquals(3, encoded.getJSONObject("saved").getInt("schema"))
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
        assertEquals(200.0, updated.table!!.corrected(next.nativeWatts, next.ad, next.rpm), 1e-8)
        assertEquals(11L, updated.table.recordedThrough)
        val restarted = GrupettoCalibrationStore.decode(GrupettoCalibrationStore.encode(updated))
        assertEquals(updated.table.gains, restarted.table!!.gains)
        assertEquals(11L, restarted.table.recordedThrough)
        assertEquals(nextCapture.samples, restarted.capture.samples)
    }

    // Independent frozen legacy lookup: migration must not accidentally use new nearest lookup.
    private fun legacyCorrected(table: GrupettoCalibrationTable, sample: CalibrationSample): Double {
        val x = calibrationCoordinate(baseline, sample.ad)!!
        val y = (sample.rpm - 45) / 5
        val i = kotlin.math.floor(x).toInt().coerceIn(0, 29)
        val j = kotlin.math.floor(y).toInt().coerceIn(0, 14)
        fun at(a: Int, b: Int) = table.gains[a * 16 + b]
        val low = at(i, j) * (1 - (x - i)) + at(i + 1, j) * (x - i)
        val high = at(i, j + 1) * (1 - (x - i)) + at(i + 1, j + 1) * (x - i)
        return sample.nativeWatts * kotlin.math.exp((low * (1 - (y - j)) + high * (y - j))
            .coerceIn(ln(.65), ln(1.35)))
    }

    private fun schemaTwo(profile: GrupettoCalibrationProfile): JSONObject =
        GrupettoCalibrationStore.encode(profile).apply {
            put("schema", 2).put("model", "unified_gaussian_log_gain_v2")
            getJSONObject("table").remove("cellSampleCounts")
        }

    @Test fun schemaTwoMigrationPreservesSurfaceHistoryAndAllowsNextSampleToContributeHalf() = withDirectory { directory ->
        val original = profile()
        val old = schemaTwo(original)
        File(directory, "grupetto-calibration.json").writeText(backup(2, old, old))
        val store = CalibrationBackupStore(directory)
        val migrated = store.load()
        assertFalse(store.resetOnLoad)
        val table = migrated.draft!!.table!!
        assertEquals(original.capture, migrated.draft.capture)
        assertEquals(original.table!!.gains, table.gains)
        assertEquals(original.table.recordedThrough, table.recordedThrough)
        assertEquals(List(496) { 1 }, table.cellSampleCounts)
        assertEquals(496, table.cells)
        assertEquals(496, table.correctionPercent.count { it != null })
        assertEquals(3, JSONObject(File(directory, "grupetto-calibration.json").readText())
            .getJSONObject("draft").getInt("schema"))
        assertEquals(migrated, CalibrationBackupStore(directory).load())
        val fresh = CalibrationSample(2000.0, 80.0, 200.0, 180.0, table.recordedThrough + 1)
        val next = updateUnifiedCalibration(migrated.draft.capture.copy(samples = samples + fresh), migrated.draft)
        assertEquals(220.0, next.table!!.corrected(200.0, 2000.0, 80.0), 1e-8) // mean of 1.3 and .9
        assertEquals(2, next.table.cellSampleCounts[167])
        assertTrue(table.gains.indices.filter { it != 167 }.all { table.gains[it] == next.table.gains[it] })
        val imported = CalibrationBackup.decode(CalibrationBackup(next, next).encode())
        store.save(imported)
        assertEquals(imported, CalibrationBackupStore(directory).load())
    }

    @Test fun schemaThreePreservesSaturatedCountAndTenthWeightAcrossRestart() = withDirectory { directory ->
        val original = profile().let { it.copy(table = it.table!!.copy(
            cellSampleCounts = List(496) { index -> if (index == 167) 10 else 0 },
            gains = List(496) { index -> if (index == 167) ln(1.1) else 0.0 }, cells = 1)) }
        val store = GrupettoCalibrationStore(directory, "direct.json")
        store.save(original)
        val restored = store.load()!!
        assertEquals(original.table!!.cellSampleCounts, restored.table!!.cellSampleCounts)
        assertEquals(original.capture, restored.capture)
        val fresh = CalibrationSample(2000.0, 80.0, 200.0, 180.0, 4000L)
        val next = updateUnifiedCalibration(restored.capture.copy(samples = samples + fresh), restored)
        assertEquals(216.0, next.table!!.corrected(200.0, 2000.0, 80.0), 1e-8)
        assertEquals(10, next.table.cellSampleCounts[167])
        store.save(next)
        assertEquals(next.table.cellSampleCounts, store.load()!!.table!!.cellSampleCounts)
    }

    @Test fun schemaThreeRejectsMalformedCountsAndCellBounds() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.remove("cellSampleCounts") },
            { it.put("cellSampleCounts", JSONArray(List(495) { 1 })) },
            { it.put("cellSampleCounts", JSONArray(List(497) { 1 })) },
            { it.getJSONArray("cellSampleCounts").put(0, -1) },
            { it.getJSONArray("cellSampleCounts").put(0, 11) },
            { it.getJSONArray("cellSampleCounts").put(0, 1.5) },
            { it.getJSONArray("cellSampleCounts").put(0, "NaN") },
            { it.put("cells", -1) },
            { it.put("cells", 497) },
        )
        mutations.forEachIndexed { index, mutation ->
            val json = GrupettoCalibrationStore.encode(profile())
            mutation(json.getJSONObject("table"))
            assertTrue("Malformed direct counts $index accepted", runCatching { GrupettoCalibrationStore.decode(json) }.isFailure)
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val directory = File(cache, "calibration-migration-${System.nanoTime()}").apply { mkdirs() }
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("calibration/$name").bufferedReader().use { it.readText() }

    @Test fun authenticReleasedSmoothedBackupUpgradesWithoutChangingAppliedGains() = withDirectory { directory ->
        val text = fixture("released-v0.0.49-v0.0.50-smoothed.json")
        val old = JSONObject(text).getJSONObject("saved")
        File(directory, "grupetto-calibration.json").writeText(text)
        val store = CalibrationBackupStore(directory)
        val migrated = store.load()
        assertFalse(store.resetOnLoad)
        val expectedGains = old.getJSONObject("table").getJSONArray("gains")
            .let { values -> List(values.length()) { values.getDouble(it) } }
        assertEquals(expectedGains, migrated.saved!!.table!!.gains)
        val observations = old.getJSONArray("samples")
        migrated.saved.capture.samples.forEachIndexed { index, sample ->
            val recorded = observations.getJSONObject(index)
            assertEquals(recorded.getDouble("pedalWatts"), sample.measuredPedalWatts, 0.0)
            assertEquals(recorded.getLong("capturedAt"), sample.capturedAt)
            assertEquals(legacyCorrected(migrated.saved.table!!, sample), sample.pedalWatts, 0.0)
        }
        val upgraded = JSONObject(File(directory, "grupetto-calibration.json").readText())
        assertEquals(2, upgraded.getInt("schema"))
        assertFalse(upgraded.getJSONObject("saved").getJSONObject("table").has("manualOffsets"))
        assertEquals(migrated, CalibrationBackupStore(directory).load())
    }

    @Test fun authenticPreCombinedFilesUpgradeWithoutRewritingRawMeasurements() = withDirectory { directory ->
        val draftText = fixture("pre-combined-draft.json")
        val savedText = fixture("pre-combined-active.json")
        File(directory, "grupetto-power-draft.json").writeText(draftText)
        File(directory, "grupetto-power-active.json").writeText(savedText)
        val store = CalibrationBackupStore(directory)
        val migrated = store.load()
        assertFalse(store.resetOnLoad)
        val old = JSONObject(savedText)
        val expectedGains = old.getJSONObject("table").getJSONArray("gains")
            .let { values -> List(values.length()) { values.getDouble(it) } }
        assertEquals(expectedGains, migrated.saved!!.table!!.gains)
        val observations = old.getJSONArray("samples")
        migrated.saved.capture.samples.forEachIndexed { index, sample ->
            assertEquals(observations.getJSONObject(index).getDouble("pedalWatts"), sample.pedalWatts, 0.0)
            assertNull(sample.recordedPedalWatts)
        }
        assertEquals(1, migrated.draft!!.capture.samples.size)
        assertEquals(draftText, File(directory, "grupetto-power-draft.json").readText())
        assertEquals(savedText, File(directory, "grupetto-power-active.json").readText())
        assertEquals(2, JSONObject(File(directory, "grupetto-calibration.json").readText()).getInt("schema"))
        assertEquals(migrated, CalibrationBackupStore(directory).load())
    }

    @Test fun oversizedStartupFilesAreQuarantinedBeforeParsing() {
        listOf("grupetto-calibration.json", "grupetto-power-draft.json").forEach { name ->
            withDirectory { directory ->
                // Exercise the size guard without allocating several copies of a 4 MiB
                // String in the API29 test process's small heap.
                val size = CalibrationBackup.MAX_BYTES + 1
                val block = ByteArray(8192) { 'x'.code.toByte() }
                File(directory, name).outputStream().buffered().use { output ->
                    var remaining = size
                    while (remaining > 0) {
                        val count = minOf(remaining, block.size)
                        output.write(block, 0, count)
                        remaining -= count
                    }
                }
                val store = CalibrationBackupStore(directory)
                assertFalse(store.load().hasData)
                assertTrue(store.resetOnLoad)
                val recovery = directory.listFiles()!!.single { it.name.startsWith("$name.unusable-") }
                assertEquals(size.toLong(), recovery.length())
                recovery.inputStream().buffered().use { input ->
                    var count = input.read(block)
                    while (count != -1) {
                        assertTrue("Quarantine must preserve every fixture byte",
                            (0 until count).all { block[it] == 'x'.code.toByte() })
                        count = input.read(block)
                    }
                }
                assertFalse(CalibrationBackupStore(directory).load().hasData)
            }
        }
    }

    @Test fun startupImmediatelyPersistsLegacyMigrationWithoutTouchingOtherFiles() = withDirectory { directory ->
        val original = profile()
        val stored = File(directory, "grupetto-calibration.json")
        stored.writeText(backup(1, legacy(original), legacy(original)))
        val unrelated = File(directory, "bike-power-calibration.json").apply { writeText("unrelated bike capture") }
        val store = CalibrationBackupStore(directory)
        val loaded = store.load()
        assertFalse(store.resetOnLoad)
        assertEquals(original.table!!.gains, loaded.saved!!.table!!.gains)
        assertEquals(2, JSONObject(stored.readText()).getInt("schema"))
        assertEquals(loaded, store.load())
        assertEquals("unrelated bike capture", unrelated.readText())
        assertFalse(directory.listFiles()!!.any { ".unusable-" in it.name })
    }

    @Test fun startupUpgradesSeparateLegacyFilesAndRetainsRecoverySources() = withDirectory { directory ->
        val original = profile()
        val draft = File(directory, "grupetto-power-draft.json").apply { writeText(legacy(original).toString()) }
        val saved = File(directory, "grupetto-power-active.json").apply { writeText(legacy(original).toString()) }
        val oldDraft = draft.readText()
        val oldSaved = saved.readText()
        val store = CalibrationBackupStore(directory)
        val loaded = store.load()
        assertFalse(store.resetOnLoad)
        assertEquals(original.capture, loaded.draft!!.capture)
        assertEquals(original.table!!.gains, loaded.saved!!.table!!.gains)
        assertEquals(2, JSONObject(File(directory, "grupetto-calibration.json").readText()).getInt("schema"))
        assertEquals(oldDraft, draft.readText())
        assertEquals(oldSaved, saved.readText())
        assertEquals(loaded, store.load())
    }

    @Test fun startupQuarantinesCorruptOrFutureCalibrationAndPreventsLegacyResurrection() {
        listOf("broken json", JSONObject(backup(1, legacy(profile()), legacy(profile())))
            .put("schema", 99).toString()).forEach { invalid ->
            withDirectory { directory ->
                val combined = File(directory, "grupetto-calibration.json").apply { writeText(invalid) }
                val legacyFiles = listOf("grupetto-power-draft.json", "grupetto-power-active.json",
                    "grupetto-power-draft.json.bak", "grupetto-power-active.json.new")
                    .map { File(directory, it).apply { writeText(legacy(profile()).toString()) } }
                val unrelated = File(directory, "user-settings.json").apply { writeText("keep settings") }
                val bikeCapture = File(directory, "bike-power-calibration.json").apply { writeText("keep bike capture") }
                val originals = (listOf(combined) + legacyFiles).associate { it.name to it.readText() }
                val store = CalibrationBackupStore(directory)
                assertFalse(store.load().hasData)
                assertTrue(store.resetOnLoad)
                assertEquals(2, JSONObject(combined.readText()).getInt("schema"))
                assertFalse(CalibrationBackup.decode(combined.readText()).hasData)
                originals.forEach { (name, contents) ->
                    val copies = directory.listFiles()!!.filter { it.name.startsWith("$name.unusable-") }
                    assertEquals("Recovery copy for $name", 1, copies.size)
                    assertEquals(contents, copies.single().readText())
                }
                assertTrue(legacyFiles.none { it.exists() })
                assertEquals("keep settings", unrelated.readText())
                assertEquals("keep bike capture", bikeCapture.readText())
                val restarted = CalibrationBackupStore(directory)
                val emptyAgain = restarted.load()
                assertFalse(emptyAgain.hasData)
                assertTrue(emptyAgain.resetAt > 0)
                assertTrue(restarted.resetOnLoad)
                assertEquals(CalibrationBackup.decode(combined.readText()).resetAt, emptyAgain.resetAt)
                // Explicit import remains strict and cannot invoke startup recovery.
                assertTrue(runCatching { CalibrationBackup.decode(invalid) }.isFailure)
                assertFalse(CalibrationBackup.decode(combined.readText()).hasData)
            }
        }
    }

    @Test fun resetMarkerSurvivesRestartUntilRealCalibrationReplacesIt() = withDirectory { directory ->
        File(directory, "grupetto-calibration.json").writeText("corrupt")
        val discarded = CalibrationBackupStore(directory).load()
        assertTrue(discarded.resetAt > 0)
        // Simulate process death before the manager can clear active/clearedAt preferences.
        val restarted = CalibrationBackupStore(directory)
        assertEquals(discarded.resetAt, restarted.load().resetAt)
        assertTrue(restarted.resetOnLoad)
        restarted.save(CalibrationBackup(profile(), profile()))
        assertTrue(restarted.load().hasData)
        assertFalse(restarted.resetOnLoad)
        assertEquals(0L, restarted.load().resetAt)
        val invalid = JSONObject(CalibrationBackup(profile(), profile()).encode()).put("resetAt", discarded.resetAt)
        assertTrue(runCatching { CalibrationBackup.decode(invalid.toString()) }.isFailure)
        val negative = JSONObject(CalibrationBackup(null, null).encode()).put("resetAt", -1L)
        assertTrue(runCatching { CalibrationBackup.decode(negative.toString()) }.isFailure)
    }

    @Test fun failedMigrationWritePreservesValidLegacySnapshotWithoutQuarantine() = withDirectory { directory ->
        val old = fixture("released-v0.0.49-v0.0.50-smoothed.json")
        val file = File(directory, "grupetto-calibration.json").apply { writeText(old) }
        val store = CalibrationBackupStore(directory)
        assertTrue(file.setWritable(false, false))
        assertTrue(directory.setWritable(false, false))
        try {
            assertTrue(runCatching { store.load() }.isFailure)
            assertFalse(store.resetOnLoad)
            assertEquals(old, file.readText())
            assertFalse(directory.listFiles()!!.any { ".unusable-" in it.name })
        } finally {
            assertTrue(directory.setWritable(true, true))
            assertTrue(file.setWritable(true, true))
        }
        assertTrue(store.load().hasData)
        assertFalse(store.resetOnLoad)
    }

    @Test fun failedQuarantineCopyLeavesOriginalCalibrationIntact() = withDirectory { directory ->
        val file = File(directory, "grupetto-calibration.json").apply { writeText("corrupt but recoverable") }
        val store = CalibrationBackupStore(directory)
        assertTrue(directory.setWritable(false, false))
        try {
            assertTrue(runCatching { store.load() }.isFailure)
            assertFalse(store.resetOnLoad)
            assertEquals("corrupt but recoverable", file.readText())
        } finally { assertTrue(directory.setWritable(true, true)) }
        assertFalse(store.load().hasData)
        assertTrue(store.resetOnLoad)
    }

    @Test fun startupReadFailureDoesNotQuarantineOrEraseOtherData() = withDirectory { directory ->
        // A path that is a directory produces a storage/read failure, not malformed JSON.
        val unreadable = File(directory, "grupetto-calibration.json").apply { mkdirs() }
        val sentinel = File(unreadable, "sentinel").apply { writeText("preserved") }
        val store = CalibrationBackupStore(directory)
        assertTrue(runCatching { store.load() }.isFailure)
        assertFalse(store.resetOnLoad)
        assertEquals("preserved", sentinel.readText())
        assertFalse(directory.listFiles()!!.any { ".unusable-" in it.name })
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
