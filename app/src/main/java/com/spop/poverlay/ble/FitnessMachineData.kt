package com.spop.poverlay.ble

import kotlin.math.roundToInt
import com.spop.poverlay.control.ResistanceLimits

/** Shared Indoor Bike Data payload for BLE and DirCon. */
internal object FitnessMachineData {
    // FTMS TS p6 CR/BV-05-C: six bytes. Match the accepted Control Point targets
    // (sint16 tenths) and increment (uint16 tenths), not the telemetry raw value.
    fun supportedResistanceRange(): ByteArray {
        val min = ResistanceLimits.MIN * ResistanceLimits.TARGET_SCALE
        val max = ResistanceLimits.MAX * ResistanceLimits.TARGET_SCALE
        val step = ResistanceLimits.STEP * ResistanceLimits.TARGET_SCALE
        return byteArrayOf(min.toByte(), (min shr 8).toByte(), max.toByte(), (max shr 8).toByte(), step.toByte(), (step shr 8).toByte())
    }

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
        val powerValue = com.spop.poverlay.sensor.power.telemetryWatts(power)
        // Legacy SIG GATT layout: sint16 whole points, NOT Control Point's tenths.
        // SmartSpin2k consumes this layout without negotiation. The current GSS
        // differs; see docs/ftms-resistance-audit.md before changing field width.
        val resistanceValue = if (resistance.isFinite()) {
            resistance.coerceIn(ResistanceLimits.MIN.toFloat(), ResistanceLimits.MAX.toFloat()).roundToInt()
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
