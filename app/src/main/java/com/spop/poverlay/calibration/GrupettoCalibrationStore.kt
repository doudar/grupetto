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

    val canActivate get() = table?.hasUsableCells == true && table.baseline == capture.baseline &&
        capture.frameSerial.isNotBlank() && capture.meterAddress.isNotBlank() && capture.serviceVersion == 2040490L &&
        capture.samples.any { it.usableForCalibration(capture.baseline) }

    // Upgrade sparse drafts without replaying recordings or changing their saved gains.
    fun withActivationReadiness() = copy(table = table?.copy(ready = canActivate))
}

/** Turning correction off retains the last usable snapshot if the next draft has no usable cell. */
internal fun activationProfile(draft: GrupettoCalibrationProfile?, saved: GrupettoCalibrationProfile?): GrupettoCalibrationProfile? =
    draft?.takeIf { it.canActivate }
        ?: saved?.takeIf { it.canActivate && (draft == null || it.matches(draft.capture)) }

/** Once activated, each usable new fit replaces the snapshot regardless of comparative error. */
internal fun automaticCalibrationProfile(draft: GrupettoCalibrationProfile, active: GrupettoCalibrationProfile?): GrupettoCalibrationProfile? =
    draft.takeIf { it.canActivate && active?.matches(it.capture) == true }

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
        try { stream.write(encode(profile).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun clear() = file.delete()

    companion object {
        internal fun decode(json: JSONObject): GrupettoCalibrationProfile {
            val schema = json.getInt("schema")
            check(schema in 1..5)
            check(json.getString("model") == when (schema) {
                1 -> "regularized_gaussian_log_gain_v1"
                2 -> "unified_gaussian_log_gain_v2"
                3 -> "direct_cell_gain_v3"
                4 -> "stable_cell_stream_v4"
                else -> "thermal_cell_stream_v5"
            })
            if (schema >= 5) check(json.getString("temperatureCorrectionModel") == CalibrationThermalCorrection.Model)
            val baseline = json.getJSONArray("baseline").let { a -> List(a.length()) { a.getInt(it) } }
            check(validCalibrationTable(baseline))
            val samples = json.getJSONArray("samples").let { a ->
                check(a.length() <= MaxCalibrationSamples)
                List(a.length()) { i ->
                    if (schema == 1) check(!a.getJSONObject(i).has("recordedPedalWatts"))
                    decodeStoredCalibrationSample(a, i, schema >= 4, schema >= 5)
                }
            }
            validateObservationRetention(baseline, samples)
            val capture = CalibrationCapture(json.getString("frameSerial"), json.getString("meterAddress"), json.getLong("serviceVersion"), baseline, samples)
            check(capture.frameSerial.isNotBlank() && capture.meterAddress.isNotBlank() && capture.serviceVersion == 2040490L)
            check(samples.all { calibrationCoordinate(baseline, it.ad) != null && it.rpm in 45.0..120.0 &&
                it.nativeWatts in CalibrationPowerRange &&
                if (it.recordedPedalWatts == null) it.pedalWatts in CalibrationPowerRange
                else it.recordedPedalWatts in CalibrationPowerRange && it.pedalWatts.isFinite() &&
                    it.pedalWatts / it.nativeWatts in (CalibrationCorrectionLimits.minimumMultiplier - 1e-9)..(CalibrationCorrectionLimits.maximumMultiplier + 1e-9) })
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
                val cells = t.getInt("cells")
                val thermalAnchors = if (schema < 5) emptyMap() else t.getJSONArray("thermalAnchors").let { a ->
                    check(a.length() == 496)
                    (0 until 496).filter { !a.isNull(it) }.associateWith { index ->
                        a.getDouble(index).also { check(it.isFinite() && it in .85..1.10) }
                    }
                }
                check(cells in 0..if (schema >= 3) 496 else 128)
                val counts = if (schema < 3) List(496) { 1 } else t.getJSONArray("cellSampleCounts").let { a ->
                    check(a.length() == 496)
                    List(496) { index ->
                        val value = a.getDouble(index)
                        check(value.isFinite() && value in 0.0..10.0 && value == value.toInt().toDouble())
                        value.toInt()
                    }
                }
                // Every old grid point is an explicit seed. Preserve its gain, but give it only
                // one observation of weight so future recordings can promptly improve it.
                GrupettoCalibrationTable(baseline, values("gains"), values("confidence"), samples.size,
                    if (schema < 3) 496 else cells,
                    t.getDouble("beforeRmse"), t.getDouble("afterRmse"), t.getBoolean("ready"),
                    recordedThrough = recordedThrough, cellSampleCounts = counts,
                    thermalCells = thermalAnchors.keys, thermalAnchors = thermalAnchors,
                    manualCoverage = if (t.has("manualCoverage")) t.getJSONArray("manualCoverage").let { a ->
                        check(a.length() <= 496)
                        List(a.length()) { a.getInt(it) }.toSet()
                    } else emptySet()).also {
                    check(it.gains.size == 496 && it.confidence.size == 496 &&
                        it.gains.indices.all { index -> it.gains[index].isFinite() && it.gains[index] in it.gainRange(index) } &&
                        it.confidence.all { value -> value in 0.0..1.0 } && it.beforeRmse.isFinite() && it.afterRmse.isFinite() &&
                        it.beforeRmse >= 0 && it.afterRmse >= 0 && it.cells in 0..496)
                    check(offsets.isEmpty() || (offsets.size == 496 && offsets.indices.all { i ->
                        offsets[i].isFinite() &&
                            it.gains[i] - offsets[i] in (kotlin.math.ln(.65) - 1e-9)..(kotlin.math.ln(1.35) + 1e-9)
                    }))
                    check(it.manualCoverage.all { cell -> cell in 0 until 496 } &&
                        (schema >= 2 || it.manualCoverage.isEmpty() || legacyManualEdits))
                    check(it.thermalCells.all { cell -> counts[cell] > 0 })
                }
            }
            // Keep the exact visible gains. Only old profiles with a manual layer need their
            // observation targets rebased; unedited recorded samples remain byte-for-byte values.
            val migrated = if (legacyManualEdits && table != null) samples.map { sample ->
                val target = legacyCorrected(table, sample)
                if (target == sample.pedalWatts) sample else sample.copy(
                    pedalWatts = target, recordedPedalWatts = sample.measuredPedalWatts)
            } else samples
            return GrupettoCalibrationProfile(capture.copy(samples = migrated), table?.withCalibrationCoverage(migrated))
                .withActivationReadiness()
        }
        /** Schema-one manual targets used the old bilinear log surface, not nearest lookup. */
        private fun legacyCorrected(table: GrupettoCalibrationTable, sample: CalibrationSample): Double {
            val x = calibrationCoordinate(table.baseline, sample.ad) ?: return sample.nativeWatts
            val y = (sample.rpm - 45) / 5
            val i = kotlin.math.floor(x).toInt().coerceIn(0, 29)
            val j = kotlin.math.floor(y).toInt().coerceIn(0, 14)
            fun at(a: Int, b: Int) = table.gains[a * 16 + b]
            val low = at(i, j) * (1 - (x - i)) + at(i + 1, j) * (x - i)
            val high = at(i, j + 1) * (1 - (x - i)) + at(i + 1, j + 1) * (x - i)
            val gain = (low * (1 - (y - j)) + high * (y - j)).coerceIn(kotlin.math.ln(.65), kotlin.math.ln(1.35))
            return sample.nativeWatts * kotlin.math.exp(gain)
        }
        internal fun encode(profile: GrupettoCalibrationProfile): JSONObject {
            val capture = profile.capture
            val json = JSONObject().put("schema", 5).put("model", "thermal_cell_stream_v5")
                .put("temperatureCorrectionModel", CalibrationThermalCorrection.Model)
                .put("frameSerial", capture.frameSerial).put("meterAddress", capture.meterAddress)
                .put("serviceVersion", capture.serviceVersion).put("baseline", JSONArray(capture.baseline))
                .put("samples", JSONArray().also { a -> capture.samples.forEach { a.put(encodeStoredCalibrationSample(it)) } })
            profile.table?.let {
                json.put("table", JSONObject().put("gains", JSONArray(it.gains)).put("confidence", JSONArray(it.confidence))
                    .put("cellSampleCounts", JSONArray(it.effectiveCellSampleCounts()))
                    .put("cells", it.cells).put("beforeRmse", it.beforeRmse).put("afterRmse", it.afterRmse).put("ready", it.ready)
                    .put("recordedThrough", it.recordedThrough).put("manualCoverage", JSONArray(it.manualCoverage.toList()))
                    .put("thermalAnchors", JSONArray().also { a -> (0 until 496).forEach { cell -> a.put(it.thermalAnchors[cell] ?: JSONObject.NULL) } }))
            }
            return json
        }
    }
}
