package com.spop.poverlay.calibration

import org.json.JSONObject

/** Parsing is separate from the model's version/range validation. A V2 missing its hub is invalid,
 * never silently downgraded to a uniform temperature. V1 needs no invented stored hub field. */
internal fun decodeThermalCheckpoint(text: String?): ThermalCheckpoint? = runCatching {
    val j = JSONObject(text ?: return@runCatching null)
    ThermalCheckpoint(j.getInt("version"), j.getDouble("celsius"), j.getDouble("roomFahrenheit"),
        j.getLong("wallMillis"), if (j.has("hubCelsius")) j.getDouble("hubCelsius") else null)
}.getOrNull()

internal fun encodeThermalCheckpoint(s: ThermalCheckpoint): String = JSONObject()
    .put("version", s.version).put("celsius", s.celsius).put("hubCelsius", s.hubCelsius)
    .put("roomFahrenheit", s.roomFahrenheit).put("wallMillis", s.wallMillis).toString()
