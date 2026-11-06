package com.spop.poverlay.util

import android.content.Context
import android.provider.Settings
import kotlin.random.Random

/**
 * The 4-hex-digit serial Grupetto advertises (as "GRUP-<serial>" manufacturer data, the
 * DIRCON identity and Device Information). Apps such as SmartSpin2k name a randomized-address
 * device after it, so it should survive a reinstall: it is derived from ANDROID_ID, which on
 * Android 8+ stays the same for an app with the same signing key on the same device and user.
 * A different signing key (official release vs. a local build) or a factory reset still gives
 * a new serial.
 */
object DeviceSerial {
    // Long-known ANDROID_ID shared by many early devices; never use it as an identity.
    private const val SHARED_ANDROID_ID = "9774d56d682e549c"

    fun fromAndroidId(androidId: String?): String? {
        val id = androidId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != SHARED_ANDROID_ID } ?: return null
        // FNV-1a, folded to 16 bits: stable across app versions, unlike String.hashCode's contract.
        var hash = 0x811C9DC5.toInt()
        for (c in id) hash = (hash xor c.code) * 0x01000193
        return format((hash xor (hash ushr 16)) and 0xFFFF)
    }

    fun generate(context: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
        return fromAndroidId(androidId) ?: format(Random.nextInt(0x10000))
    }

    private fun format(value: Int) = value.toString(16).padStart(4, '0').uppercase()
}
