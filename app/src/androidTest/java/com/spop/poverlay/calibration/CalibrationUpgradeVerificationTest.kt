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
                assertEquals(2, profile.getInt("schema"))
                assertEquals("unified_gaussian_log_gain_v2", profile.getString("model"))
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
                assertEquals(rawSamples.length(), profile.capture.samples.size)
                profile.capture.samples.forEachIndexed { index, sample ->
                    val original = rawSamples.getJSONObject(index)
                    assertEquals(original.getDouble("ad"), sample.ad, 0.0)
                    assertEquals(original.getDouble("rpm"), sample.rpm, 0.0)
                    assertEquals(original.getDouble("nativeWatts"), sample.nativeWatts, 0.0)
                    assertEquals(original.getLong("capturedAt"), sample.capturedAt)
                    assertEquals("Original pedal evidence must survive manual-target migration",
                        original.getDouble("pedalWatts"), sample.measuredPedalWatts, 0.0)
                    assertEquals(table.corrected(sample.nativeWatts, sample.ad, sample.rpm), sample.pedalWatts, 0.0)
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
            assertEquals("A future stable recording replaces the old edit exactly", 240.0,
                updated.corrected(200.0, fresh.ad, fresh.rpm), 1e-8)
            assertEquals(capture.samples.size + 1, restarted.state.value.count)
            assertTrue(restarted.state.value.recording)
            assertTrue(restarted.state.value.active)
            restarted.verifyBaseline(capture.baseline)
            val corrected = restarted.correctedPower(CalibrationFrame(SystemClock.elapsedRealtime(),
                fresh.ad.toInt(), fresh.rpm, fresh.nativeWatts, capture.frameSerial, 1))
            assertNotNull(corrected)
            assertEquals(240.0, corrected!!.toDouble(), 1e-5)
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
