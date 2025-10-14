package com.spop.poverlay.sensor.heartrate

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.Closeable
import java.util.UUID

/**
 * Connects to nearby Bluetooth Low Energy heart rate monitors (BLE Heart Rate Profile)
 * and exposes the most recent beats-per-minute reading as a hot flow.
 */
class HeartRateMonitor(private val context: Context) : Closeable {

    companion object {
        private val HEART_RATE_SERVICE_UUID: UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_UUID: UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val RESTART_SCAN_DELAY_MS = 5_000L
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableHeartRate = MutableStateFlow(Float.NaN)
    val heartRate: StateFlow<Float> = mutableHeartRate

    @Volatile
    private var isStarted = false

    // Thread confinement: all scanner interactions happen on the main thread
    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            Timber.i("Heart rate device found: %s (%s)", device.name, device.address)
            stopScanInternal()
            connectToDevice(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.w("Heart rate scan failed: %d", errorCode)
            restartScanWithDelay()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Heart rate GATT connection error: status=%d", status)
                gatt.close()
                mutableHeartRate.value = Float.NaN
                restartScanWithDelay()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("Connected to heart rate device")
                    mainHandler.post {
                        bluetoothGatt = gatt
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("Heart rate device disconnected")
                    gatt.close()
                    bluetoothGatt = null
                    mutableHeartRate.value = Float.NaN
                    restartScanWithDelay()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Heart rate services discover failed: %d", status)
                restartScanWithDelay()
                return
            }

            val service = gatt.getService(HEART_RATE_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            if (service == null || characteristic == null) {
                Timber.w("Heart rate measurement characteristic missing")
                restartScanWithDelay()
                return
            }

            val notificationEnabled = gatt.setCharacteristicNotification(characteristic, true)
            if (!notificationEnabled) {
                Timber.w("Failed to enable heart rate notifications")
                restartScanWithDelay()
                return
            }

            val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            if (descriptor == null) {
                Timber.w("Heart rate CCC descriptor missing")
                restartScanWithDelay()
                return
            }

            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val wrote = gatt.writeDescriptor(descriptor)
            if (!wrote) {
                Timber.w("Failed to write heart rate CCC descriptor")
                restartScanWithDelay()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Heart rate notifications enabled")
            } else {
                Timber.w("Failed to enable heart rate notifications: %d", status)
                restartScanWithDelay()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != HEART_RATE_MEASUREMENT_UUID) {
                return
            }
            val data = characteristic.value ?: return
            if (data.isEmpty()) {
                return
            }
            val flags = data[0].toInt()
            val format16Bit = flags and 0x01 != 0
            val bpm = if (format16Bit) {
                if (data.size >= 3) {
                    val lower = data[1].toInt() and 0xFF
                    val upper = data[2].toInt() and 0xFF
                    ((upper shl 8) or lower).toFloat()
                } else {
                    Float.NaN
                }
            } else {
                if (data.size >= 2) {
                    (data[1].toInt() and 0xFF).toFloat()
                } else {
                    Float.NaN
                }
            }

            if (bpm.isFinite() && bpm > 0f) {
                mutableHeartRate.value = bpm
            } else {
                mutableHeartRate.value = Float.NaN
            }
        }
    }

    fun start() {
        if (isStarted) {
            return
        }
        isStarted = true
        mutableHeartRate.value = Float.NaN
        startScanInternal()
    }

    fun stop() {
        if (!isStarted) {
            return
        }
        isStarted = false
        stopScanInternal()
        disconnectGatt()
        mutableHeartRate.value = Float.NaN
    }

    override fun close() {
        stop()
    }

    private fun startScanInternal() {
        if (!isStarted) {
            return
        }
        if (!hasScanPermission() || !hasConnectPermission()) {
            Timber.w("Missing Bluetooth permissions for heart rate monitor")
            return
        }
        stopScanInternal()
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            Timber.w("Bluetooth adapter unavailable or disabled")
            restartScanWithDelay()
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            Timber.w("Bluetooth LE scanner unavailable")
            restartScanWithDelay()
            return
        }
        this.scanner = scanner

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        mainHandler.post {
            try {
                scanner.startScan(filters, settings, scanCallback)
                Timber.i("Started scanning for heart rate monitors")
            } catch (sec: SecurityException) {
                Timber.e(sec, "Bluetooth scan failed due to missing permission")
            }
        }
    }

    private fun stopScanInternal() {
        val localScanner = scanner ?: return
        mainHandler.post {
            try {
                localScanner.stopScan(scanCallback)
            } catch (sec: SecurityException) {
                Timber.e(sec, "Failed to stop heart rate scan")
            }
        }
        scanner = null
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            Timber.w("Missing BLUETOOTH_CONNECT permission; cannot connect to %s", device.address)
            restartScanWithDelay()
            return
        }
        disconnectGatt()
        mainHandler.post {
            try {
                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, false, gattCallback)
                }
            } catch (sec: SecurityException) {
                Timber.e(sec, "Failed to connect to heart rate device")
                restartScanWithDelay()
            }
        }
    }

    private fun disconnectGatt() {
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null
        mainHandler.post {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (sec: SecurityException) {
                Timber.e(sec, "Failed to close heart rate GATT")
            }
        }
    }

    private fun restartScanWithDelay() {
        if (!isStarted) {
            return
        }
        mainHandler.postDelayed({
            if (isStarted) {
                startScanInternal()
            }
        }, RESTART_SCAN_DELAY_MS)
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
