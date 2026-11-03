package com.spop.poverlay.calibration

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

internal data class GrupettoCalibrationProfile(val capture: CalibrationCapture, val table: GrupettoCalibrationTable?) {
    fun matches(other: CalibrationCapture) = capture.frameSerial == other.frameSerial &&
        capture.serviceVersion == other.serviceVersion &&
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
        return decode(stream.use { JSONObject(boundedCalibrationText(it)) })
    }
    fun save(profile: GrupettoCalibrationProfile) {
        val stream = file.startWrite()
        try { stream.write(encode(profile).toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()

    companion object {
        internal fun decode(json: JSONObject): GrupettoCalibrationProfile {
            val schema = json.getInt("schema")
            check(schema in 1..2)
            check(json.getString("model") == if (schema == 1) "regularized_gaussian_log_gain_v1" else "unified_gaussian_log_gain_v2")
            val baseline = json.getJSONArray("baseline").let { a -> List(a.length()) { a.getInt(it) } }
            check(validCalibrationTable(baseline))
            val samples = json.getJSONArray("samples").let { a ->
                check(a.length() <= 3600)
                List(a.length()) { i -> a.getJSONObject(i).let {
                    check(schema == 2 || !it.has("recordedPedalWatts"))
                    CalibrationSample(it.getDouble("ad"), it.getDouble("rpm"), it.getDouble("nativeWatts"),
                        it.getDouble("pedalWatts"), it.getLong("capturedAt"),
                        recordedPedalWatts = if (it.has("recordedPedalWatts")) it.getDouble("recordedPedalWatts") else null)
                } }
            }
            val capture = CalibrationCapture(json.getString("frameSerial"), json.getString("meterAddress"), json.getLong("serviceVersion"), baseline, samples)
            check(capture.frameSerial.isNotBlank() && capture.meterAddress.isNotBlank() && capture.serviceVersion == 2040490L)
            check(samples.all { calibrationCoordinate(baseline, it.ad) != null && it.rpm in 45.0..120.0 &&
                it.nativeWatts in CalibrationPowerRange &&
                if (it.recordedPedalWatts == null) it.pedalWatts in CalibrationPowerRange
                else it.recordedPedalWatts in CalibrationPowerRange && it.pedalWatts.isFinite() &&
                    it.pedalWatts / it.nativeWatts in (.65 - 1e-9)..(1.35 + 1e-9) })
            var legacyManualEdits = false
            val table = (if (json.has("table")) json.getJSONObject("table") else null)?.let { t ->
                fun values(key: String) = t.getJSONArray(key).let { a -> List(a.length()) { a.getDouble(it) } }
                // Legacy offsets are validated before being folded into the one saved surface.
                // They are never retained as an additive layer or reapplied to a future fit.
                check(schema == 1 || !t.has("manualOffsets"))
                val offsets = if (t.has("manualOffsets")) values("manualOffsets") else emptyList()
                legacyManualEdits = offsets.isNotEmpty()
                val recordedThrough = if (schema == 1)
                    maxOf(System.currentTimeMillis(), samples.maxOfOrNull { it.capturedAt } ?: Long.MIN_VALUE)
                else t.getLong("recordedThrough")
                check(recordedThrough >= (samples.filter { it.usableForCalibration(baseline) }
                    .maxOfOrNull { it.capturedAt } ?: Long.MIN_VALUE))
                GrupettoCalibrationTable(baseline, values("gains"), values("confidence"), samples.size, t.getInt("cells"),
                    t.getDouble("beforeRmse"), t.getDouble("afterRmse"), t.getBoolean("ready"),
                    recordedThrough = recordedThrough,
                    manualCoverage = if (t.has("manualCoverage")) t.getJSONArray("manualCoverage").let { a ->
                        check(a.length() <= 496)
                        List(a.length()) { a.getInt(it) }.toSet()
                    } else emptySet()).also {
                    check(it.gains.size == 496 && it.confidence.size == 496 &&
                        it.gains.all { value -> value.isFinite() && value in kotlin.math.ln(.65)..kotlin.math.ln(1.35) } &&
                        it.confidence.all { value -> value in 0.0..1.0 } && it.beforeRmse.isFinite() && it.afterRmse.isFinite() &&
                        it.beforeRmse >= 0 && it.afterRmse >= 0 && it.cells in 0..128)
                    check(offsets.isEmpty() || (offsets.size == 496 && offsets.indices.all { i ->
                        offsets[i].isFinite() &&
                            it.gains[i] - offsets[i] in (kotlin.math.ln(.65) - 1e-9)..(kotlin.math.ln(1.35) + 1e-9)
                    }))
                    check(it.manualCoverage.all { cell -> cell in 0 until 496 } &&
                        (schema == 2 || it.manualCoverage.isEmpty() || legacyManualEdits))
                }
            }
            // Keep the exact visible gains. Only old profiles with a manual layer need their
            // observation targets rebased; unedited recorded samples remain byte-for-byte values.
            val migrated = if (legacyManualEdits && table != null) samplesForCalibrationTable(samples, table) else samples
            return GrupettoCalibrationProfile(capture.copy(samples = migrated), table?.withCalibrationCoverage(migrated))
        }
        internal fun encode(profile: GrupettoCalibrationProfile): JSONObject {
            val capture = profile.capture
            val json = JSONObject().put("schema", 2).put("model", "unified_gaussian_log_gain_v2")
                .put("frameSerial", capture.frameSerial).put("meterAddress", capture.meterAddress)
                .put("serviceVersion", capture.serviceVersion).put("baseline", JSONArray(capture.baseline))
                .put("samples", JSONArray().also { a -> capture.samples.forEach {
                    val sample = JSONObject().put("ad", it.ad).put("rpm", it.rpm).put("nativeWatts", it.nativeWatts)
                        .put("pedalWatts", it.pedalWatts).put("capturedAt", it.capturedAt)
                    it.recordedPedalWatts?.let { watts -> sample.put("recordedPedalWatts", watts) }
                    a.put(sample)
                } })
            profile.table?.let {
                json.put("table", JSONObject().put("gains", JSONArray(it.gains)).put("confidence", JSONArray(it.confidence))
                    .put("cells", it.cells).put("beforeRmse", it.beforeRmse).put("afterRmse", it.afterRmse).put("ready", it.ready)
                    .put("recordedThrough", it.recordedThrough).put("manualCoverage", JSONArray(it.manualCoverage.toList())))
            }
            return json
        }
    }
}
