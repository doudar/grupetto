package com.spop.poverlay.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import androidx.annotation.RequiresApi
import timber.log.Timber

/** Called under the server monitor, shared with characteristic writers. */
@Suppress("DEPRECATION")
internal fun sendGattNotification(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    confirm: Boolean,
    value: ByteArray
): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return sendGattNotificationWithValue(server, device, characteristic, confirm, value)
    }
    // The legacy API reads the value synchronously. Preserve the latest read/DIRCON value.
    val currentValue = characteristic.value
    return try {
        characteristic.value = value
        server.notifyCharacteristicChanged(device, characteristic, confirm)
    } catch (e: SecurityException) {
        Timber.w(e, "Missing Bluetooth permission while submitting notification")
        false
    } finally {
        characteristic.value = currentValue
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun sendGattNotificationWithValue(
    server: BluetoothGattServer,
    device: BluetoothDevice,
    characteristic: BluetoothGattCharacteristic,
    confirm: Boolean,
    value: ByteArray
): Boolean = try {
    server.notifyCharacteristicChanged(device, characteristic, confirm, value) == BluetoothStatusCodes.SUCCESS
} catch (e: SecurityException) {
    Timber.w(e, "Missing Bluetooth permission while submitting notification")
    false
}
