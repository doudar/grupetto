package com.spop.poverlay.calibration

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Copied into the released checkout, never compiled against the new calibration implementation.
 * The old app's real fitter, editing tools and stores create the data left behind by install -r.
 */
class CalibrationUpgradeSeedTest {
    @Suppress("DEPRECATION")
    @Test fun persistReleasedCalibrationForUpgrade() {
        val arguments = InstrumentationRegistry.getArguments()
        assertEquals("Only the disposable emulator upgrade script may seed this fixture", "true",
            arguments.getString("upgradeTest"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val legacyTag = arguments.getString("legacyTag")!!
        assertEquals(legacyTag, installed.versionName)
        assertEquals(arguments.getString("legacyVersionCode")!!.toInt(), installed.versionCode)

        val baseline = List(31) { 1000 + it * 100 }
        val samples = (8..16 step 2).flatMap { resistance ->
            listOf(65, 80, 95).map { rpm ->
                val native = 80.0 + resistance * 8
                CalibrationSample(baseline[resistance].toDouble(), rpm.toDouble(), native,
                    native * 1.1, (resistance * 100 + rpm).toLong())
            }
        }
        val capture = CalibrationCapture("upgrade-test-bike", "upgrade-test-pedals", 2040490L,
            baseline, samples)
        val fitted = GrupettoCalibrationFitter.fit(baseline, samples)!!
        assertTrue("The actual released fitter must produce a usable table", fitted.ready)
        var edited = CalibrationManualEdit.setPercent(fitted, setOf(10 * 16 + 7), -12.5, true)
        repeat(7) { edited = CalibrationManualEdit.smooth(edited, (0 until 496).toSet()) }
        assertEquals(496, edited.manualOffsets.size)
        assertTrue(edited.gains != fitted.gains)

        val profile = GrupettoCalibrationProfile(capture, edited)
        CalibrationBackupStore(context.filesDir).save(CalibrationBackup(profile, profile))
        val legacyJson = JSONObject(File(context.filesDir, "grupetto-calibration.json").readText())
        assertEquals(1, legacyJson.getInt("schema"))
        listOf("draft", "saved").forEach { key ->
            val stored = legacyJson.getJSONObject(key)
            assertEquals(1, stored.getInt("schema"))
            assertEquals("regularized_gaussian_log_gain_v1", stored.getString("model"))
            assertEquals(496, stored.getJSONObject("table").getJSONArray("manualOffsets").length())
        }
        assertEquals(edited.gains, CalibrationBackupStore(context.filesDir).load().saved!!.table!!.gains)
        assertTrue(context.getSharedPreferences("grupetto-power-calibration", Context.MODE_PRIVATE)
            .edit().putBoolean("active", true).commit())

        // This separate sensor-board capture and unrelated app settings must survive unchanged.
        CalibrationStore(context.filesDir).save(capture, CalibrationFitter.fit(baseline, samples))
        val rawCapture = File(context.filesDir, "bike-power-calibration.json").readText()
        val sentinel = "Unrelated app data from $legacyTag"
        assertTrue(context.getSharedPreferences("configuration", Context.MODE_PRIVATE).edit()
            .putString("bleFtmsDeviceName", sentinel).putBoolean("showShifters", false).commit())
        File(context.filesDir, "upgrade-unrelated-sentinel.txt").writeText(sentinel)

        // Expectations are emitted by the old binary, not reconstructed by the new serializer.
        val expected = JSONObject().put("fixtureSchema", 1).put("legacyTag", legacyTag)
            .put("legacyVersionCode", installed.versionCode).put("sentinel", sentinel)
            .put("gains", JSONArray(edited.gains)).put("confidence", JSONArray(edited.confidence))
            .put("manualCoverage", JSONArray(edited.manualCoverage.toList()))
            .put("rawCapture", rawCapture).put("legacyBackup", legacyJson)
        File(context.filesDir, "calibration-upgrade-fixture.json").writeText(expected.toString(2))
    }
}
