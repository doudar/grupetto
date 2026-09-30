package com.spop.poverlay.sensor.heartrate

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import timber.log.Timber

/** Permission checks are repeated per operation because grants can change during a session. */
internal class HeartRateBluetoothAccess(
    private val context: Context,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val adapter: () -> BluetoothAdapter? = { BluetoothAdapter.getDefaultAdapter() }
) {
    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canConnect(): Boolean = granted(
        if (sdkInt >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT
        else Manifest.permission.BLUETOOTH
    )

    fun canScan(): Boolean = canConnect() &&
        granted(if (sdkInt >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN
                else Manifest.permission.BLUETOOTH_ADMIN) &&
        // The manifest does not assert neverForLocation, so scanning still needs location.
        (sdkInt < Build.VERSION_CODES.M || granted(Manifest.permission.ACCESS_FINE_LOCATION))

    fun startScan(filters: List<ScanFilter>, settings: ScanSettings, callback: ScanCallback): Boolean {
        if (!canScan()) return false
        return try {
            val scanner = adapter()?.bluetoothLeScanner ?: return false
            scanner.startScan(filters, settings, callback)
            true
        } catch (e: SecurityException) {
            Timber.w(e, "HR scan permission was revoked")
            false
        } catch (e: IllegalStateException) {
            Timber.w(e, "Bluetooth is unavailable for HR scanning")
            false
        }
    }

    fun stopScan(callback: ScanCallback) {
        // Cleanup is best effort even after revocation; callers clear their local state.
        try {
            adapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: SecurityException) {
            Timber.w(e, "HR scan permission was revoked before cleanup")
        } catch (e: IllegalStateException) {
            Timber.w(e, "Bluetooth is unavailable for HR scan cleanup")
        }
    }

    fun remoteDevice(address: String): BluetoothDevice? {
        if (!canConnect()) return null
        return try {
            adapter()?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Invalid HR device address: %s", address)
            null
        }
    }

    fun deviceInfo(device: BluetoothDevice): HeartRateDevice? {
        if (!canConnect()) return null
        return try {
            HeartRateDevice(device.address, device.name)
        } catch (e: SecurityException) {
            Timber.w(e, "HR device permission was revoked")
            null
        }
    }

    fun connect(device: BluetoothDevice, callback: BluetoothGattCallback): BluetoothGatt? {
        if (!canConnect()) return null
        return try {
            device.connectGatt(context, false, callback)
        } catch (e: SecurityException) {
            Timber.w(e, "HR connection permission was revoked")
            null
        }
    }

    fun discoverServices(gatt: BluetoothGatt): Boolean {
        if (!canConnect()) return false
        return try {
            gatt.discoverServices()
        } catch (e: SecurityException) {
            Timber.w(e, "HR service discovery permission was revoked")
            false
        }
    }

    fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        descriptor: BluetoothGattDescriptor
    ): Boolean {
        if (!canConnect()) return false
        return try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) return false
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        } catch (e: SecurityException) {
            Timber.w(e, "HR notification permission was revoked")
            false
        }
    }

    fun disconnect(gatt: BluetoothGatt) {
        try {
            gatt.disconnect()
        } catch (e: SecurityException) {
            Timber.w(e, "HR disconnect permission was revoked")
        } catch (e: IllegalStateException) {
            Timber.w(e, "HR connection is unavailable during disconnect")
        }
    }

    fun close(gatt: BluetoothGatt) {
        try {
            gatt.close()
        } catch (e: SecurityException) {
            Timber.w(e, "HR close permission was revoked")
        } catch (e: IllegalStateException) {
            Timber.w(e, "HR connection is unavailable during close")
        }
    }
}
