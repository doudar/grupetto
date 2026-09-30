package com.spop.poverlay.sensor.power

/** CPS 0x2A63: flags UINT16, instantaneous power SINT16 watts, optional fields. */
internal fun decodeCyclingPower(bytes: ByteArray): Int? {
    if (bytes.size < 4) return null
    val flags = (bytes[0].toInt() and 255) or ((bytes[1].toInt() and 255) shl 8)
    // Validate flagged optional fields too; truncated packets must not refresh freshness.
    val fields = mapOf(0 to 1, 2 to 2, 4 to 6, 5 to 4, 6 to 4, 7 to 4, 8 to 3, 9 to 2, 10 to 2, 11 to 2)
    val required = 4 + fields.filterKeys { flags and (1 shl it) != 0 }.values.sum()
    // CPP collectors ignore RFU flag bits and additional data. P715 meters set
    // bits 13/14; rejecting them discards valid watts and triggers reconnects.
    if (bytes.size < required) return null
    return ((bytes[2].toInt() and 255) or (bytes[3].toInt() shl 8)).toShort().toInt()
}

data class ExternalPowerReading(val watts: Int, val timestamp: Long, val address: String)

internal fun freshExternalPower(reading: ExternalPowerReading?, now: Long): Float? =
    reading?.takeIf { now - it.timestamp in 0..3000 }?.watts?.coerceAtLeast(0)?.toFloat()
