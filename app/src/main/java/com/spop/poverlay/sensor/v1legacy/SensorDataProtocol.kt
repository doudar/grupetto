package com.spop.poverlay.sensor.v1legacy

/** Fixed, read-only V1 telemetry requests. No arbitrary commands or calibration operations. */
internal enum class SensorDataRead(val command: String, private val opcode: Int, private val digits: Int) {
    Cadence("F5,41,36,F6", 0x41, 3),
    Power("F5,44,39,F6", 0x44, 5),
    Resistance("F5,49,3E,F6", 0x49, 3);

    /** Null means missing/invalid data, including the service's TIME_OUT response. */
    fun decodeResponse(hex: String?): Float? {
        if (hex == null || hex.length > 128) return null
        val tokens = hex.trim().split(Regex("[\\s,]+"))
        if (tokens.size != digits + 5) return null
        val bytes = tokens.map { token ->
            if (token.length != 2 || token.any { it.digitToIntOrNull(16) == null }) return null
            token.toInt(16)
        }
        if (bytes[0] != 0xF1 || bytes[1] != opcode || bytes[2] != digits || bytes.last() != 0xF6) return null
        if ((bytes.dropLast(2).sum() and 0xFF) != bytes[bytes.lastIndex - 1]) return null
        var value = 0
        for (index in (3 until 3 + digits).reversed()) {
            val digit = bytes[index] - 0x30
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        // Peloton SensorData 2.8.3016 V1DataExtractor.atof: power has one decimal place.
        return when (this) {
            Power -> value / 10f
            Resistance -> value.toFloat().takeIf { it in 0f..100f }
            Cadence -> value.toFloat()
        }
    }
}
