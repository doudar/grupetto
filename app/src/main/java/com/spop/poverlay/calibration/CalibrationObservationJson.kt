package com.spop.poverlay.calibration

import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Compact stream tuples avoid a map and repeated field names per second at maximum capacity.
 * Layout: kind, AD, rpm, native, target, wall time, original pedal/null, native time, pedal time,
 * accepted duration, new-level marker. Legacy aggregates retain their descriptive object layout. */
internal fun encodeStoredCalibrationSample(sample: CalibrationSample): Any {
    val observation = sample.observation
    if (observation != null) return JSONArray().put("paired_second").put(sample.ad).put(sample.rpm)
        .put(sample.nativeWatts).put(sample.pedalWatts).put(sample.capturedAt)
        .put(sample.recordedPedalWatts ?: JSONObject.NULL).put(observation.nativeAt).put(observation.pedalAt)
        .put(observation.durationMillis).put(observation.startsNewLevel)
    return encodeCalibrationObservation(JSONObject().put("ad", sample.ad).put("rpm", sample.rpm)
        .put("nativeWatts", sample.nativeWatts).put("pedalWatts", sample.pedalWatts).put("capturedAt", sample.capturedAt)
        .also { sample.recordedPedalWatts?.let { value -> it.put("recordedPedalWatts", value) } }, null)
}

internal fun decodeStoredCalibrationSample(samples: JSONArray, index: Int, provenance: Boolean): CalibrationSample {
    val value = samples.get(index)
    if (value is JSONArray) {
        require(provenance && value.length() == 11 && value.getString(0) == "paired_second")
        val observation = CalibrationObservation(value.getLong(7), value.getLong(8), value.getInt(9), value.getBoolean(10))
        require(observation.nativeAt >= 0 && observation.pedalAt >= 0 &&
            kotlin.math.abs(observation.nativeAt - observation.pedalAt) <= 1500 && observation.durationMillis == 1000)
        return CalibrationSample(value.getDouble(1), value.getDouble(2), value.getDouble(3), value.getDouble(4),
            value.getLong(5), if (value.isNull(6)) null else value.getDouble(6), observation)
    }
    require(value is JSONObject)
    return CalibrationSample(value.getDouble("ad"), value.getDouble("rpm"), value.getDouble("nativeWatts"),
        value.getDouble("pedalWatts"), value.getLong("capturedAt"),
        if (value.has("recordedPedalWatts")) value.getDouble("recordedPedalWatts") else null,
        if (provenance) decodeCalibrationObservation(value) else null)
}

internal fun encodeCalibrationObservation(json: JSONObject, observation: CalibrationObservation?): JSONObject {
    json.put("kind", if (observation == null) "legacy_effort_aggregate" else "paired_second")
    observation?.let { json.put("observation", JSONObject().put("nativeAt", it.nativeAt)
        .put("pedalAt", it.pedalAt).put("durationMillis", it.durationMillis).put("startsNewLevel", it.startsNewLevel)) }
    return json
}

internal fun decodeCalibrationObservation(json: JSONObject): CalibrationObservation? {
    val kind = json.getString("kind")
    if (kind == "legacy_effort_aggregate") { require(!json.has("observation")); return null }
    require(kind == "paired_second") { "Unknown calibration evidence kind." }
    val value = json.getJSONObject("observation")
    return CalibrationObservation(value.getLong("nativeAt"), value.getLong("pedalAt"),
        value.getInt("durationMillis"), value.getBoolean("startsNewLevel")).also {
        require(it.nativeAt >= 0 && it.pedalAt >= 0 && kotlin.math.abs(it.nativeAt - it.pedalAt) <= 1500 &&
            it.durationMillis == 1000) { "Invalid paired observation provenance." }
    }
}

/** Never overwrite the only copy of an older format. A successful fsynced copy is required
 * before atomic migration. This file is recovery evidence, never an automatic learning source. */
internal fun preserveCalibrationMigrationSource(source: File) {
    val recovery = File(source.path + ".before-stream-v4-" + UUID.randomUUID())
    check(recovery.createNewFile())
    try {
        source.inputStream().use { input -> FileOutputStream(recovery).use { out -> input.copyTo(out); out.fd.sync() } }
    } catch (error: Exception) { recovery.delete(); throw error }
}

internal fun validateObservationRetention(baseline: List<Int>, samples: List<CalibrationSample>) {
    require(samples.count { it.observation == null } <= 3600)
    val stream = samples.filter { it.observation != null }
    require(stream.groupBy { calibrationCellIndex(baseline, it.ad, it.rpm) }.all { (cell, group) ->
        cell != null && group.size <= CalibrationCellSeconds && group.map {
            // elapsedRealtime restarts with Android; wall time separates observations from later boots.
            Triple(it.capturedAt, it.observation!!.nativeAt, it.observation.pedalAt)
        }.distinct().size == group.size
    }) { "Invalid or oversized per-cell observation bucket." }
}
