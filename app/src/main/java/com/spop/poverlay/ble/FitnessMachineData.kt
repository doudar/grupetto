package com.spop.poverlay.ble

import kotlin.math.roundToInt

/** Shared Indoor Bike Data payload for BLE and DirCon. */
internal object FitnessMachineData {
    private const val MAX_RESISTANCE = 100

    fun supportedResistanceRange(): ByteArray = byteArrayOf(
        0x01, 0x00, // minimum: 1
        MAX_RESISTANCE.toByte(), 0x00,
        0x01, 0x00 // increment: 1
    )

    /**
     * [heartRate] is the relayed strap reading in bpm. When it is 1..255 it is appended as the
     * FTMS Heart Rate field (uint8, after Instantaneous Power), so apps that read only the
     * Indoor Bike Data, as with a SmartSpin2k, get heart rate without pairing a second sensor.
     */
    fun encode(cadence: Float, power: Float, speed: Float, resistance: Float, heartRate: Int? = null): ByteArray {
        val hr = heartRate?.takeIf { it in 1..255 }
        // More Data is clear, so instantaneous speed is included.
        val flags = FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousCadencePresent or
            FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousPowerPresent or
            FitnessMachineConstants.IndoorBikeDataFlags.ResistanceLevelPresent or
            (if (hr != null) FitnessMachineConstants.IndoorBikeDataFlags.HeartRatePresent else 0)
        val speedValue = (speed * 1.60934f * 100).toInt() // mph -> 0.01 km/h
        val cadenceValue = (cadence * 2).toInt()
        val powerValue = power.toInt()
        // Preserve the existing whole-level, 16-bit wire format. Round rather than
        // truncate: 99.99999 must encode as 100. Zero is also used for no sensor data.
        val resistanceValue = if (resistance.isFinite()) {
            resistance.coerceIn(0f, MAX_RESISTANCE.toFloat()).roundToInt()
        } else {
            0
        }

        val packet = byteArrayOf(
            (flags and 0xFF).toByte(),
            (flags shr 8 and 0xFF).toByte(),
            (speedValue and 0xFF).toByte(),
            (speedValue shr 8 and 0xFF).toByte(),
            (cadenceValue and 0xFF).toByte(),
            (cadenceValue shr 8 and 0xFF).toByte(),
            (resistanceValue and 0xFF).toByte(),
            (resistanceValue shr 8 and 0xFF).toByte(),
            (powerValue and 0xFF).toByte(),
            (powerValue shr 8 and 0xFF).toByte()
        )
        // Field order per FTMS 4.9: Heart Rate follows Average Power and Expended Energy,
        // neither of which is sent.
        return if (hr != null) packet + hr.toByte() else packet
    }
}
