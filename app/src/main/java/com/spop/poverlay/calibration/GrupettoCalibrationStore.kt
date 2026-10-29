package com.spop.poverlay.calibration

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal data class GrupettoCalibrationProfile(val capture: CalibrationCapture, val table: GrupettoCalibrationTable?) {
    fun matches(other: CalibrationCapture) = capture.frameSerial == other.frameSerial &&
        capture.meterAddress == other.meterAddress && capture.serviceVersion == other.serviceVersion &&
        capture.baseline == other.baseline
}

/** Turning correction off retains the last usable snapshot, even if the next draft lacks coverage. */
internal fun activationProfile(draft: GrupettoCalibrationProfile?, saved: GrupettoCalibrationProfile?): GrupettoCalibrationProfile? =
    draft?.takeIf { it.table?.ready == true }
        ?: saved?.takeIf { it.table?.ready == true && (draft == null || it.matches(draft.capture)) }

/** Once activated, each usable new fit replaces the snapshot regardless of comparative error. */
internal fun automaticCalibrationProfile(draft: GrupettoCalibrationProfile, active: GrupettoCalibrationProfile?): GrupettoCalibrationProfile? =
    draft.takeIf { it.table?.ready == true && active?.matches(it.capture) == true }

internal class GrupettoCalibrationStore(directory: File, name: String) {
    private val file = AtomicFile(File(directory, name))
    fun load(): GrupettoCalibrationProfile? {
        val stream = try { file.openRead() } catch (e: FileNotFoundException) {
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw e
            return null
        }
        return decode(stream.bufferedReader().use { JSONObject(it.readText()) })
    }
    fun save(profile: GrupettoCalibrationProfile) {
        val stream = file.startWrite()
        try { stream.write(encode(profile).toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()

    companion object {
        internal fun decode(json: JSONObject): GrupettoCalibrationProfile {
            check(json.getInt("schema") == 1)
            check(json.getString("model") == "regularized_gaussian_log_gain_v1")
            val baseline = json.getJSONArray("baseline").let { a -> List(a.length()) { a.getInt(it) } }
            check(validCalibrationTable(baseline))
            val samples = json.getJSONArray("samples").let { a ->
                check(a.length() <= 3600)
                List(a.length()) { i -> a.getJSONObject(i).let {
                    CalibrationSample(it.getDouble("ad"), it.getDouble("rpm"), it.getDouble("nativeWatts"), it.getDouble("pedalWatts"), it.getLong("capturedAt"))
                } }
            }
            val capture = CalibrationCapture(json.getString("frameSerial"), json.getString("meterAddress"), json.getLong("serviceVersion"), baseline, samples)
            check(capture.frameSerial.isNotBlank() && capture.meterAddress.isNotBlank() && capture.serviceVersion == 2040490L)
            check(samples.all { calibrationCoordinate(baseline, it.ad) != null && it.rpm in 45.0..120.0 &&
                it.nativeWatts in CalibrationPowerRange && it.pedalWatts in CalibrationPowerRange })
            val table = (if (json.has("table")) json.getJSONObject("table") else null)?.let { t ->
                fun values(key: String) = t.getJSONArray(key).let { a -> List(a.length()) { a.getDouble(it) } }
                GrupettoCalibrationTable(baseline, values("gains"), values("confidence"), samples.size, t.getInt("cells"),
                    t.getDouble("beforeRmse"), t.getDouble("afterRmse"), t.getBoolean("ready")).also {
                    check(it.gains.size == 496 && it.confidence.size == 496 &&
                        it.gains.all { value -> value.isFinite() && value in kotlin.math.ln(.65)..kotlin.math.ln(1.35) } &&
                        it.confidence.all { value -> value in 0.0..1.0 } && it.beforeRmse.isFinite() && it.afterRmse.isFinite() &&
                        it.beforeRmse >= 0 && it.afterRmse >= 0 && it.cells in 0..128)
                }
            }
            return GrupettoCalibrationProfile(capture, table?.copy(correctionPercent = calibrationCorrectionPercent(table, samples)))
        }
        internal fun encode(profile: GrupettoCalibrationProfile): JSONObject {
            val capture = profile.capture
            val json = JSONObject().put("schema", 1).put("model", "regularized_gaussian_log_gain_v1")
                .put("frameSerial", capture.frameSerial).put("meterAddress", capture.meterAddress)
                .put("serviceVersion", capture.serviceVersion).put("baseline", JSONArray(capture.baseline))
                .put("samples", JSONArray().also { a -> capture.samples.forEach {
                    a.put(JSONObject().put("ad", it.ad).put("rpm", it.rpm).put("nativeWatts", it.nativeWatts)
                        .put("pedalWatts", it.pedalWatts).put("capturedAt", it.capturedAt))
                } })
            profile.table?.let {
                json.put("table", JSONObject().put("gains", JSONArray(it.gains)).put("confidence", JSONArray(it.confidence))
                    .put("cells", it.cells).put("beforeRmse", it.beforeRmse).put("afterRmse", it.afterRmse).put("ready", it.ready))
            }
            return json
        }
    }
}
