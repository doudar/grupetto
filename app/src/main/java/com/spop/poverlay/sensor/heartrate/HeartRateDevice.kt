package com.spop.poverlay.sensor.heartrate

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult

/**
 * Represents a Bluetooth heart rate monitor discovered or paired by the app.
 */
data class HeartRateDevice(
    val name: String?,
    val address: String
) {
    val displayName: String
        get() = when {
            !name.isNullOrBlank() -> name
            else -> "Heart Rate Monitor ($address)"
        }

    companion object {
        fun from(device: BluetoothDevice, fallbackName: String? = null): HeartRateDevice =
            HeartRateDevice(
                name = device.name ?: fallbackName,
                address = device.address
            )

        fun from(result: ScanResult): HeartRateDevice =
            from(
                device = result.device,
                fallbackName = result.scanRecord?.deviceName
            )
    }
}
