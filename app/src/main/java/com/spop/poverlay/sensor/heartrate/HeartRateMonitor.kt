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
import android.os.SystemClock
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.io.Closeable
import java.util.UUID

enum class HeartRateConnectionState {
    Idle,
    Scanning,
    Connecting,
    Connected
}

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
        private const val MEASUREMENT_STALE_TIMEOUT_MS = 10_000L
    }

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val mutableHeartRate = MutableStateFlow(Float.NaN)
    val heartRate: StateFlow<Float> = mutableHeartRate

    private val mutableAvailableDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val availableDevices: StateFlow<List<HeartRateDevice>> = mutableAvailableDevices

    private val mutableConnectedDevice = MutableStateFlow<HeartRateDevice?>(null)
    val connectedDevice: StateFlow<HeartRateDevice?> = mutableConnectedDevice

    private val mutableConnectionState = MutableStateFlow(HeartRateConnectionState.Idle)
    val connectionState: StateFlow<HeartRateConnectionState> = mutableConnectionState

    @Volatile
    private var isStarted = false

    // Thread confinement: all scanner interactions happen on the main thread
    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private val preferredDeviceAddresses = mutableSetOf<String>()
    private var pendingConnectAddress: String? = null
    private var heartRateCharacteristic: BluetoothGattCharacteristic? = null
    private var lastMeasurementTimestamp = 0L
    private val measurementWatchdog = Runnable { checkMeasurementWatchdog() }

    private fun updateAvailableDevices(device: HeartRateDevice) {
        val existing = mutableAvailableDevices.value
        val updated = when {
            existing.any { it.address.equals(device.address, true) } ->
                existing.map { if (it.address.equals(device.address, true)) device else it }
            else -> existing + device
        }
        mutableAvailableDevices.value = updated
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val deviceInfo = HeartRateDevice.from(result)
            Timber.i("Heart rate device found: %s (%s)", deviceInfo.name, deviceInfo.address)
            updateAvailableDevices(deviceInfo)

            val shouldConnect = when {
                pendingConnectAddress != null -> device.address.equals(pendingConnectAddress, true)
                mutableConnectedDevice.value == null && preferredDeviceAddresses.contains(device.address) -> true
                else -> false
            }

            if (shouldConnect) {
                pendingConnectAddress = device.address
                stopScanInternal()
                connectToDevice(device)
            }
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
                mutableConnectedDevice.value = null
                mutableConnectionState.value = if (isStarted) {
                    HeartRateConnectionState.Scanning
                } else {
                    HeartRateConnectionState.Idle
                }
                restartScanWithDelay()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("Connected to heart rate device")
                    mutableConnectedDevice.value = HeartRateDevice.from(gatt.device)
                    pendingConnectAddress = null
                    mutableConnectionState.value = HeartRateConnectionState.Connected
                    mainHandler.post {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            }
                        } catch (sec: SecurityException) {
                            Timber.w(sec, "Failed to request high connection priority")
                        }
                        bluetoothGatt = gatt
                        gatt.discoverServices()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("Heart rate device disconnected")
                    gatt.close()
                    bluetoothGatt = null
                    mutableHeartRate.value = Float.NaN
                    mutableConnectedDevice.value = null
                    mutableConnectionState.value = if (isStarted) {
                        HeartRateConnectionState.Scanning
                    } else {
                        HeartRateConnectionState.Idle
                    }
                    mainHandler.removeCallbacks(measurementWatchdog)
                    heartRateCharacteristic = null
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

            heartRateCharacteristic = characteristic

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
            } else {
                scheduleMeasurementWatchdog()
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
                requestInitialMeasurement()
                scheduleMeasurementWatchdog()
            } else {
                Timber.w("Failed to enable heart rate notifications: %d", status)
                restartScanWithDelay()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Heart rate characteristic read failed: %d", status)
                return
            }
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                processHeartRateSample(characteristic)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                processHeartRateSample(characteristic)
            }
        }
    }

    private fun processHeartRateSample(characteristic: BluetoothGattCharacteristic) {
        val data = characteristic.value ?: return
        if (data.isEmpty()) {
            scheduleMeasurementWatchdog()
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
            lastMeasurementTimestamp = SystemClock.elapsedRealtime()
        } else {
            mutableHeartRate.value = Float.NaN
        }
        scheduleMeasurementWatchdog()
    }

    private fun requestInitialMeasurement() {
        val characteristic = heartRateCharacteristic ?: return
        val gatt = bluetoothGatt ?: return
        mainHandler.post {
            try {
                val issued = gatt.readCharacteristic(characteristic)
                Timber.i("Requested initial heart rate read: %s", issued)
            } catch (sec: SecurityException) {
                Timber.e(sec, "Failed to read initial heart rate characteristic")
            }
        }
    }

    private fun scheduleMeasurementWatchdog() {
        if (!isStarted) {
            return
        }
        if (mutableConnectionState.value != HeartRateConnectionState.Connected) {
            return
        }
        mainHandler.removeCallbacks(measurementWatchdog)
        mainHandler.postDelayed(measurementWatchdog, MEASUREMENT_STALE_TIMEOUT_MS)
    }

    private fun checkMeasurementWatchdog() {
        if (!isStarted) {
            return
        }
        if (mutableConnectionState.value != HeartRateConnectionState.Connected) {
            return
        }
        val lastTimestamp = lastMeasurementTimestamp
        val now = SystemClock.elapsedRealtime()
        if (lastTimestamp > 0 && now - lastTimestamp < MEASUREMENT_STALE_TIMEOUT_MS) {
            scheduleMeasurementWatchdog()
            return
        }

        Timber.w("Heart rate data stale; attempting refresh")
        val characteristic = heartRateCharacteristic
        val gatt = bluetoothGatt
        if (characteristic != null && gatt != null) {
            val readIssued = try {
                gatt.readCharacteristic(characteristic)
            } catch (sec: SecurityException) {
                Timber.e(sec, "Failed to issue stale heart rate read")
                false
            }
            if (readIssued) {
                scheduleMeasurementWatchdog()
            } else {
                restartConnection()
            }
        } else {
            restartConnection()
        }
    }

    private fun restartConnection() {
        val nextAddress = mutableConnectedDevice.value?.address ?: pendingConnectAddress
        Timber.w("Restarting heart rate connection to %s", nextAddress ?: "unknown")
        disconnectGatt()
        if (nextAddress != null) {
            connect(nextAddress)
        } else if (isStarted) {
            startScanInternal()
        }
    }

    fun start() {
        if (isStarted) {
            return
        }
        isStarted = true
        mutableHeartRate.value = Float.NaN
        mutableConnectionState.value = HeartRateConnectionState.Scanning
        mutableAvailableDevices.value = emptyList()
        lastMeasurementTimestamp = 0L
        mainHandler.removeCallbacks(measurementWatchdog)
        startScanInternal()
    }

    fun stop() {
        if (!isStarted) {
            return
        }
        isStarted = false
        stopScanInternal()
        disconnectGatt()
        heartRateCharacteristic = null
        mainHandler.removeCallbacks(measurementWatchdog)
        lastMeasurementTimestamp = 0L
        mutableHeartRate.value = Float.NaN
        pendingConnectAddress = null
        mutableConnectionState.value = HeartRateConnectionState.Idle
        mutableAvailableDevices.value = emptyList()
    }

    fun setPreferredDevices(devices: List<HeartRateDevice>) {
        preferredDeviceAddresses.clear()
        preferredDeviceAddresses.addAll(devices.map { it.address })
        if (isStarted && mutableConnectedDevice.value == null) {
            devices.firstOrNull()?.let { connect(it.address) }
        }
    }

    fun connect(address: String) {
        preferredDeviceAddresses.add(address)
        pendingConnectAddress = address
        val adapter = bluetoothManager?.adapter
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (iae: IllegalArgumentException) {
            Timber.w(iae, "Invalid Bluetooth address: %s", address)
            null
        }
        start()
        if (device != null) {
            stopScanInternal()
            connectToDevice(device)
        } else {
            startScanInternal()
        }
    }

    fun disconnect() {
        pendingConnectAddress = null
        disconnectGatt()
        if (isStarted) {
            startScanInternal()
        } else {
            mutableConnectionState.value = HeartRateConnectionState.Idle
        }
    }

    fun refreshDiscovery() {
        if (!isStarted) {
            start()
            return
        }
        stopScanInternal()
        mutableAvailableDevices.value = emptyList()
        mutableConnectionState.value = HeartRateConnectionState.Scanning
        startScanInternal()
    }

    fun forgetDevice(address: String) {
        preferredDeviceAddresses.remove(address)
        if (mutableConnectedDevice.value?.address.equals(address, true)) {
            disconnect()
        }
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
        mutableConnectionState.value = HeartRateConnectionState.Scanning
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
        if (!isStarted) {
            mutableConnectionState.value = HeartRateConnectionState.Idle
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            Timber.w("Missing BLUETOOTH_CONNECT permission; cannot connect to %s", device.address)
            restartScanWithDelay()
            return
        }
        mutableConnectionState.value = HeartRateConnectionState.Connecting
        mutableConnectedDevice.value = HeartRateDevice.from(device)
        disconnectGatt()
        mainHandler.post {
            try {
                bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(context, false, gattCallback)
                }
                lastMeasurementTimestamp = 0L
                mainHandler.removeCallbacks(measurementWatchdog)
                heartRateCharacteristic = null
            } catch (sec: SecurityException) {
                Timber.e(sec, "Failed to connect to heart rate device")
                mutableConnectionState.value = if (isStarted) {
                    HeartRateConnectionState.Scanning
                } else {
                    HeartRateConnectionState.Idle
                }
                mutableConnectedDevice.value = null
                restartScanWithDelay()
            }
        }
    }

    private fun disconnectGatt() {
        val gatt = bluetoothGatt ?: return
        bluetoothGatt = null
        mutableConnectionState.value = if (isStarted) HeartRateConnectionState.Scanning else HeartRateConnectionState.Idle
        mutableConnectedDevice.value = null
        heartRateCharacteristic = null
        mainHandler.removeCallbacks(measurementWatchdog)
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
            mutableConnectionState.value = HeartRateConnectionState.Idle
            return
        }
        mutableConnectionState.value = HeartRateConnectionState.Scanning
        mainHandler.removeCallbacks(measurementWatchdog)
        heartRateCharacteristic = null
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
