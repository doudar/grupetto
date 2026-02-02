package com.spop.poverlay.sensor.heartrate

import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal heart rate central: scans for HR service, connects to first device, and exposes BPM as a StateFlow<Int?>.
 * Designed to be lightweight and not interfere with existing TX/advertising code.
 */
object HeartRateManager {
    private const val ScanTimeoutMs = 10_000L
    private const val MinBackoffMs = 1_000L
    private const val MaxBackoffMs = 30_000L

    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var scannerCallback: ScanCallback? = null

    @Volatile
    private var appContext: Context? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private val stopped = AtomicBoolean(true)

    @Synchronized
    fun start(context: Context) {
        try {
            appContext = context.applicationContext
            stop()
            stopped.set(false)
            startScanLoop()
        } catch (ex: Exception) {
            Timber.w(ex, "HeartRateManager start failed")
        }
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        scanJob?.cancel()
        scanJob = null
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {}
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        scannerCallback?.let {
            try {
                BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(it)
            } catch (_: Exception) {}
        }
        scannerCallback = null
        _heartRate.value = null
        Timber.i("HeartRateManager stopped")
    }

    private fun startScanLoop() {
        if (scanJob?.isActive == true) return
        val context = appContext ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Timber.w("No Bluetooth adapter available for HeartRateManager")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Timber.w("No BLE scanner available for HeartRateManager")
            return
        }

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanJob = scope.launch {
            var backoffMs = MinBackoffMs
            while (isActive && !stopped.get() && bluetoothGatt == null) {
                var device = scanOnce(scanner, listOf(filter), settings, logAll = false)
                if (device == null) {
                    // fallback: scan all and log devices seen (and pick the first advertising HR service)
                    device = scanOnce(scanner, null, settings, logAll = true)
                }

                if (device != null && !stopped.get()) {
                    backoffMs = MinBackoffMs
                    connect(context, device)
                    // Give connection a moment; if it fails, loop will rescan.
                    delay(1_500L)
                } else if (!stopped.get()) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MaxBackoffMs)
                }
            }
        }
    }

    private suspend fun scanOnce(
        scanner: BluetoothLeScanner,
        filters: List<ScanFilter>?,
        settings: ScanSettings,
        logAll: Boolean
    ): BluetoothDevice? {
        val foundDevice = CompletableDeferred<BluetoothDevice?>()
        val seenAddresses = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (logAll) {
                    val address = device.address ?: "unknown"
                    if (seenAddresses.add(address)) {
                        val uuids = result.scanRecord?.serviceUuids?.joinToString(",") { it.uuid.toString() }
                        Timber.i("HR scan saw device name=%s address=%s uuids=%s", device.name, address, uuids)
                    }
                    val hasHrService = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
                    if (hasHrService && !foundDevice.isCompleted) {
                        foundDevice.complete(device)
                    }
                } else {
                    if (!foundDevice.isCompleted) {
                        foundDevice.complete(device)
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.isNotEmpty()) {
                    onScanResult(0, results[0])
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("HeartRate scan failed: %d", errorCode)
                if (!foundDevice.isCompleted) {
                    foundDevice.complete(null)
                }
            }
        }

        scannerCallback = callback
        try {
            if (filters != null) {
                scanner.startScan(filters, settings, callback)
                Timber.i("Started HR scan (filtered)")
            } else {
                scanner.startScan(null, settings, callback)
                Timber.i("Started HR scan (scan-all)")
            }
        } catch (sec: SecurityException) {
            Timber.w(sec, "Failed to start HR scan (missing permission?)")
            scannerCallback = null
            return null
        }

        val device = withTimeoutOrNull(ScanTimeoutMs) { foundDevice.await() }
        try {
            scanner.stopScan(callback)
        } catch (ex: Exception) {
            Timber.w(ex, "stopScan failed")
        }
        scannerCallback = null
        return device
    }

    private fun connect(context: Context, device: BluetoothDevice) {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}

        bluetoothGatt = device.connectGatt(context.applicationContext, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w("HR GATT error: %d", status)
                    try { gatt.close() } catch (_: Exception) {}
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    if (!stopped.get()) startScanLoop()
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Timber.i("HR device connected")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.i("HR device disconnected")
                        try { gatt.close() } catch (_: Exception) {}
                        if (bluetoothGatt === gatt) bluetoothGatt = null
                        _heartRate.value = null
                        if (!stopped.get()) startScanLoop()
                    }
                }

            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Timber.w("HR services discover failed: %d", status)
                    return
                }
                val service = gatt.getService(HR_SERVICE) ?: return
                val characteristic = service.getCharacteristic(HR_MEASUREMENT) ?: return
                val notificationEnabled = gatt.setCharacteristicNotification(characteristic, true)
                if (!notificationEnabled) {
                    Timber.w("Failed to enable HR notifications")
                    return
                }
                val desc = characteristic.getDescriptor(CCC_UUID) ?: return
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                try {
                    gatt.writeDescriptor(desc)
                } catch (sec: SecurityException) {
                    Timber.w(sec, "Failed to write CCC descriptor for HR")
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == HR_MEASUREMENT) {
                    val data = characteristic.value ?: return
                    if (data.size < 2) return
                    val flags = data[0].toInt()
                    val format16 = flags and 0x01 != 0
                    val bpm = if (format16) {
                        if (data.size >= 3) ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else null
                    } else {
                        (data[1].toInt() and 0xFF)
                    }
                    val intBpm = bpm as? Int
                    if (intBpm != null && intBpm > 0) {
                        _heartRate.value = intBpm
                    }
                }
            }
        })
    }
}
