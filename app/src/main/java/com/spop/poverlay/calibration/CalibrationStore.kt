package com.spop.poverlay.calibration

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

data class CalibrationCapture(val frameSerial: String, val meterAddress: String,
    val serviceVersion: Long, val baseline: List<Int>, val samples: List<CalibrationSample>)

/** Atomic private storage preserves the baseline and observations across app restarts.
 * Enabling collection is intentionally not persisted: a restart never resumes learning silently.
 */
internal class CalibrationStore(directory: File) {
    private val file = AtomicFile(File(directory, "bike-power-calibration.json"))
    fun load(): CalibrationCapture? {
        // Let AtomicFile recover its backup before deciding that no capture exists.
        val input = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return null
        }
        val json = JSONObject(input.bufferedReader().use { it.readText() })
        check(json.getInt("schema") == 1)
        val baseline = json.getJSONArray("baseline").let { a -> List(a.length()) { a.getInt(it) } }
        check(validCalibrationTable(baseline))
        val samples = json.getJSONArray("samples").let { a ->
            check(a.length() <= 3600)
            List(a.length()) { i -> a.getJSONObject(i).let {
                CalibrationSample(it.getDouble("ad"), it.getDouble("rpm"), it.getDouble("nativeWatts"),
                    it.getDouble("pedalWatts"), it.getLong("capturedAt"))
            } }
        }
        check(samples.all { it.ad.isFinite() && it.ad >= baseline.first() && it.ad <= baseline.last() &&
            it.rpm in 45.0..120.0 && it.nativeWatts in CalibrationPowerRange && it.pedalWatts in CalibrationPowerRange })
        check(json.getString("frameSerial").isNotBlank() && json.getString("meterAddress").isNotBlank())
        return CalibrationCapture(json.getString("frameSerial"), json.getString("meterAddress"),
            json.getLong("serviceVersion"), baseline, samples)
    }
    fun save(capture: CalibrationCapture, fit: CalibrationFit) {
        val json = JSONObject().put("schema", 1).put("destination", "sensor_board_candidate_only")
            .put("boardWriteSupported", false).put("applied", false)
            .put("frameSerial", capture.frameSerial).put("meterAddress", capture.meterAddress)
            .put("serviceVersion", capture.serviceVersion).put("baseline", JSONArray(capture.baseline))
            .put("candidate", JSONArray(fit.points)).put("supportedPoints", JSONArray(fit.supportedPoints))
            .put("readyForReview", fit.ready).put("cadenceConflicts", fit.conflicts)
            .put("validationCount", fit.validationCount)
            .put("beforeRmse", fit.beforeRmse ?: JSONObject.NULL).put("afterRmse", fit.afterRmse ?: JSONObject.NULL)
            .put("samples", JSONArray().also { array -> capture.samples.forEach {
                array.put(JSONObject().put("ad", it.ad).put("rpm", it.rpm).put("nativeWatts", it.nativeWatts)
                    .put("pedalWatts", it.pedalWatts).put("capturedAt", it.capturedAt))
            } })
        val stream = file.startWrite()
        try { stream.write(json.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()
}
