package com.spop.poverlay.control

/** Peloton points, shared by the UI, controller, motor writer and FTMS encoders. */
object ResistanceLimits {
    const val MIN = 0
    const val MAX = 100
    const val STEP = 1
    const val TARGET_SCALE = 10
    val levels = MIN..MAX

    fun targetStatus(level: Int): ByteArray {
        require(level in levels)
        val raw = level * TARGET_SCALE
        return byteArrayOf(7, raw.toByte(), (raw shr 8).toByte())
    }
}
