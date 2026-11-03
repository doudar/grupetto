package com.spop.poverlay.calibration

import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Runs after a real released APK is replaced in place, without clearing app data. */
class CalibrationUpgradeVerificationTest {
    private fun JSONArray.doubles() = List(length()) { getDouble(it) }
    private fun JSONArray.integers() = List(length()) { getInt(it) }

    @Test fun releasedInstallMigratesAndAcceptsFutureRecordings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(context.filesDir, "calibration-upgrade-fixture.json")
        if (InstrumentationRegistry.getArguments().getString("upgradeTest") == "true") {
            assertTrue("Old APK fixture is missing: the upgrade must retain app data", marker.isFile)
        } else {
            assumeTrue("Only the old-to-new APK upgrade job supplies this fixture", marker.isFile)
        }
        val expected = JSONObject(marker.readText())
        assertEquals(1, expected.getInt("fixtureSchema"))
        val legacyTag = expected.getString("legacyTag")
        assertTrue(legacyTag in setOf("v0.0.49", "v0.0.50"))
        val gains = expected.getJSONArray("gains").doubles()
        val confidence = expected.getJSONArray("confidence").doubles()
        val manualCoverage = expected.getJSONArray("manualCoverage").integers().toSet()
        val rawSamples = expected.getJSONObject("legacyBackup").getJSONObject("draft")
            .getJSONArray("samples")
        val calibrationFile = File(context.filesDir, "grupetto-calibration.json")

        fun assertUnrelatedDataUnchanged() {
            assertEquals("Raw sensor-board capture must remain byte-for-byte unchanged",
                expected.getString("rawCapture"), File(context.filesDir, "bike-power-calibration.json").readText())
            assertEquals(expected.getString("sentinel"),
                File(context.filesDir, "upgrade-unrelated-sentinel.txt").readText())
            val preferences = context.getSharedPreferences("configuration", Context.MODE_PRIVATE)
            assertEquals(expected.getString("sentinel"), preferences.getString("bleFtmsDeviceName", null))
            assertFalse(preferences.getBoolean("showShifters", true))
        }

        fun assertUnifiedFile(): JSONObject = JSONObject(calibrationFile.readText()).also { json ->
            assertEquals("Migration must be persisted automatically, not wait for a manual save", 2,
                json.getInt("schema"))
            listOf("draft", "saved").forEach { key ->
                val profile = json.getJSONObject(key)
                assertEquals(3, profile.getInt("schema"))
                assertEquals("direct_cell_gain_v3", profile.getString("model"))
                assertFalse(profile.getJSONObject("table").has("manualOffsets"))
            }
        }

        withContext(Dispatchers.Main) {
            // exportBackup awaits manager initialization but does not trigger a save.
            val manager = GrupettoCalibrationManager(context)
            val loaded = CalibrationBackup.decode(manager.exportBackup())
            assertTrue("The existing activation preference should survive the upgrade", manager.state.value.active)
            assertFalse("A fresh process still requires bike identity verification", manager.state.value.verified)
            assertTrue(context.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE)
                .getBoolean("active", false))
            listOf(loaded.draft!!, loaded.saved!!).forEach { profile ->
                val table = profile.table!!
                assertEquals("The exact visible old surface must survive", gains, table.gains)
                assertEquals(confidence, table.confidence)
                assertEquals(manualCoverage, table.manualCoverage)
                assertEquals(List(496) { 1 }, table.cellSampleCounts)
                assertEquals(rawSamples.length(), profile.capture.samples.size)
                profile.capture.samples.forEachIndexed { index, sample ->
                    val original = rawSamples.getJSONObject(index)
                    assertEquals(original.getDouble("ad"), sample.ad, 0.0)
                    assertEquals(original.getDouble("rpm"), sample.rpm, 0.0)
                    assertEquals(original.getDouble("nativeWatts"), sample.nativeWatts, 0.0)
                    assertEquals(original.getLong("capturedAt"), sample.capturedAt)
                    assertEquals("Original pedal evidence must survive manual-target migration",
                        original.getDouble("pedalWatts"), sample.measuredPedalWatts, 0.0)
                    val x = calibrationCoordinate(table.baseline, sample.ad)!!
                    val y = (sample.rpm - 45) / 5
                    val i = kotlin.math.floor(x).toInt().coerceIn(0, 29)
                    val j = kotlin.math.floor(y).toInt().coerceIn(0, 14)
                    fun at(a: Int, b: Int) = gains[a * 16 + b]
                    val low = at(i, j) * (1 - (x - i)) + at(i + 1, j) * (x - i)
                    val high = at(i, j + 1) * (1 - (x - i)) + at(i + 1, j + 1) * (x - i)
                    val legacyTarget = sample.nativeWatts * kotlin.math.exp(
                        (low * (1 - (y - j)) + high * (y - j)).coerceIn(kotlin.math.ln(.65), kotlin.math.ln(1.35)))
                    assertEquals("Keep the legacy bilinear target; do not reinterpret it with new lookup", legacyTarget, sample.pedalWatts, 0.0)
                }
                assertTrue(profile.capture.samples.any { it.recordedPedalWatts != null })
            }
            assertEquals(gains, manager.state.value.displayTable!!.gains)
            assertUnifiedFile()
            assertUnrelatedDataUnchanged()

            // Reopen from the actual migrated disk snapshot: no second smoothing/rebasing pass.
            val restarted = GrupettoCalibrationManager(context)
            val reloaded = CalibrationBackup.decode(restarted.exportBackup())
            assertEquals(loaded, reloaded)
            assertTrue(restarted.state.value.active)

            val capture = reloaded.draft!!.capture
            restarted.record(true, null)
            val before = restarted.state.value.displayTable!!
            assertEquals(gains, before.gains)
            val window = CalibrationWindow(capture.baseline)
            val recordedAt = maxOf(System.currentTimeMillis(), before.recordedThrough + 1)
            var accepted: CalibrationSample? = null
            repeat(window.settings.totalSeconds) { second ->
                val timestamp = second * 1000L
                val frame = CalibrationFrame(timestamp, capture.baseline[10], 80.0, 200.0,
                    capture.frameSerial, 1)
                accepted = window.add(frame, 240, timestamp, capture.meterAddress, timestamp, recordedAt)
            }
            val fresh = accepted ?: error("The real capture window rejected a stable effort")
            assertTrue("The new observation must differ from the legacy manual surface",
                abs(before.corrected(fresh.nativeWatts, fresh.ad, fresh.rpm) - fresh.pedalWatts) > 1.0)
            restarted.accept(capture.copy(samples = capture.samples + fresh))
            val updated = restarted.state.value.displayTable!!
            val expectedWatts = (before.corrected(200.0, fresh.ad, fresh.rpm) + 240.0) / 2
            assertEquals("A future stable recording averages with the migrated one-value seed", expectedWatts,
                updated.corrected(200.0, fresh.ad, fresh.rpm), 1e-8)
            val changedCell = updated.currentCellIndex(fresh.ad, fresh.rpm)!!
            assertEquals(2, updated.cellSampleCounts[changedCell])
            updated.gains.indices.filter { it != changedCell }.forEach {
                assertEquals("Recording cannot modify a neighboring migrated cell", before.gains[it], updated.gains[it], 0.0)
            }
            assertEquals(capture.samples.size + 1, restarted.state.value.count)
            assertTrue(restarted.state.value.recording)
            assertTrue(restarted.state.value.active)
            restarted.verifyBaseline(capture.baseline)
            val corrected = restarted.correctedPower(CalibrationFrame(SystemClock.elapsedRealtime(),
                fresh.ad.toInt(), fresh.rpm, fresh.nativeWatts, capture.frameSerial, 1))
            assertNotNull(corrected)
            assertEquals(expectedWatts, corrected!!.toDouble(), 1e-5)
            restarted.record(false, null)

            val afterRestart = GrupettoCalibrationManager(context)
            val persisted = CalibrationBackup.decode(afterRestart.exportBackup())
            assertEquals(updated.gains, persisted.draft!!.table!!.gains)
            assertEquals(updated.gains, persisted.saved!!.table!!.gains)
            assertEquals(fresh, persisted.draft.capture.samples.last())
            persisted.draft.capture.samples.take(rawSamples.length()).forEachIndexed { index, sample ->
                assertEquals(rawSamples.getJSONObject(index).getDouble("pedalWatts"), sample.measuredPedalWatts, 0.0)
            }
            assertTrue(afterRestart.state.value.active)
            assertUnifiedFile()
            assertUnrelatedDataUnchanged()
        }
        // An ordinary full instrumentation suite must skip this one-shot upgrade-only fixture.
        assertTrue(marker.delete())
    }
}
