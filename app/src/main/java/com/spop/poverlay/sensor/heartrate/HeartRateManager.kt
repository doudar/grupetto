package com.spop.poverlay.sensor.heartrate

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelUuid
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class HeartRateDevice(
    val address: String,
    val name: String?
)

/**
 * Heart rate manager with explicit user selection.
 * - No auto-connect to the first device found.
 * - Scans only when requested from UI.
 */
object HeartRateManager {
    private const val PrefsName = "heart_rate"
    private const val PrefSavedDevices = "hr_saved_devices"
    private const val PrefSelectedDevice = "hr_selected_device"
    private const val PrefNamePrefix = "hr_name_"

    private const val ReconnectDelayMs = 3_000L
    private const val AutoReconnectScanMs = 10_000L
    private const val StaleHeartRateTimeoutMs = 12_000L

    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate

    private val _connectedDevice = MutableStateFlow<HeartRateDevice?>(null)
    val connectedDevice: StateFlow<HeartRateDevice?> = _connectedDevice

    private val _discoveredDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<HeartRateDevice>> = _discoveredDevices

    private val _savedDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val savedDevices: StateFlow<List<HeartRateDevice>> = _savedDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var discoveryCallback: ScanCallback? = null

    @Volatile
    private var appContext: Context? = null

    private var prefs: SharedPreferences? = null
    private var selectedAddress: String? = null
    private val stopped = AtomicBoolean(true)
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var manageSessionJob: kotlinx.coroutines.Job? = null
    private var isManaging = false
    @Volatile
    private var lastHeartRateAtMs: Long = 0L
    @Volatile
    private var lastConnectedAtMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start(context: Context) {
        try {
            if (appContext != null && bluetoothGatt != null) {
                return
            }
            appContext = context.applicationContext
            prefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            stopped.set(false)
            loadSavedDevices()
            selectedAddress = prefs?.getString(PrefSelectedDevice, null)
            Timber.tag("HRM").i("start: selectedAddress=%s saved=%d", selectedAddress, _savedDevices.value.size)
            if (_savedDevices.value.isNotEmpty()) {
                startAutoReconnectScan()
            } else {
                selectedAddress?.let { connectToAddress(it) }
            }
        } catch (ex: Exception) {
            Timber.w(ex, "HeartRateManager start failed")
        }
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        manageSessionJob?.cancel()
        manageSessionJob = null
        isManaging = false
        stopDiscovery()
        try {
            bluetoothGatt?.disconnect()
        } catch (_: Exception) {}
        try {
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        _heartRate.value = null
        _connectedDevice.value = null
        Timber.i("HeartRateManager stopped")
    }

    fun startDiscovery() {
        val context = appContext ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run {
            Timber.w("No Bluetooth adapter available for HeartRateManager")
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            Timber.w("No BLE scanner available for HeartRateManager")
            return
        }
        if (_isScanning.value) return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val hasHrService = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
                if (!hasHrService) return
                Timber.tag("HRM").d("discovered: name=%s address=%s", device.name, device.address)
                if (maybeAutoConnectSaved(device)) return
                addDiscoveredDevice(device)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Timber.w("HeartRate scan failed: %d", errorCode)
            }
        }

        discoveryCallback = callback
        try {
            scanner.startScan(listOf(filter), settings, callback)
            _isScanning.value = true
            Timber.tag("HRM").i("Started HR discovery scan")
        } catch (sec: SecurityException) {
            Timber.w(sec, "Failed to start HR scan (missing permission?)")
            discoveryCallback = null
        }
    }

    fun stopDiscovery() {
        val callback = discoveryCallback ?: return
        try {
            BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(callback)
        } catch (_: Exception) {}
        discoveryCallback = null
        _isScanning.value = false
        Timber.tag("HRM").i("Stopped HR discovery scan")
    }

    fun setManaging(active: Boolean) {
        isManaging = active
        if (!active) {
            manageSessionJob?.cancel()
            manageSessionJob = null
            return
        }
        manageSessionJob?.cancel()
        manageSessionJob = scope.launch {
            while (isManaging && !stopped.get()) {
                val connected = _connectedDevice.value
                if (connected != null) {
                    val lastSignalAtMs = maxOf(lastHeartRateAtMs, lastConnectedAtMs)
                    if (lastSignalAtMs == 0L) {
                        continue
                    }
                    val ageMs = System.currentTimeMillis() - lastSignalAtMs
                    if (ageMs > StaleHeartRateTimeoutMs) {
                        Timber.tag("HRM").i("stale HR detected (%dms), disconnecting", ageMs)
                        try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
                        _connectedDevice.value = null
                        _heartRate.value = null
                        lastConnectedAtMs = 0L
                        lastHeartRateAtMs = 0L
                    }
                }
                delay(1_000L)
            }
        }
    }

    fun connectTo(device: HeartRateDevice) {
        Timber.tag("HRM").i("connectTo: name=%s address=%s", device.name, device.address)
        saveDevice(device)
        selectedAddress = device.address
        connectToAddress(device.address)
    }

    fun forgetDevice(address: String) {
        Timber.tag("HRM").i("forgetDevice: address=%s", address)
        val prefs = prefs ?: return
        val saved = prefs.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(address)
        prefs.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + address)
            if (selectedAddress == address) {
                remove(PrefSelectedDevice)
            }
        }
        if (selectedAddress == address) {
            selectedAddress = null
            try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
        }
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun saveDevice(device: HeartRateDevice) {
        val prefs = prefs ?: return
        val saved = prefs.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.add(device.address)
        prefs.edit {
            putStringSet(PrefSavedDevices, saved)
            putString(PrefSelectedDevice, device.address)
            if (!device.name.isNullOrBlank()) {
                putString(PrefNamePrefix + device.address, device.name)
            }
        }
        Timber.tag("HRM").i("saveDevice: name=%s address=%s", device.name, device.address)
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun loadSavedDevices() {
        val prefs = prefs ?: return
        val saved = prefs.getStringSet(PrefSavedDevices, emptySet()).orEmpty()
        val devices = saved.map {
            HeartRateDevice(it, prefs.getString(PrefNamePrefix + it, null))
        }
        _savedDevices.value = devices.sortedBy { it.name ?: it.address }
    }

    private fun addDiscoveredDevice(device: BluetoothDevice) {
        val address = device.address ?: return
        if (address == selectedAddress) return
        val savedAddresses = _savedDevices.value.map { it.address }.toSet()
        if (savedAddresses.contains(address)) return

        val name = device.name
        val current = _discoveredDevices.value.toMutableList()
        if (current.none { it.address == address }) {
            current.add(HeartRateDevice(address, name))
            _discoveredDevices.value = current.sortedBy { it.name ?: it.address }
        }
    }

    private fun maybeAutoConnectSaved(device: BluetoothDevice): Boolean {
        if (_connectedDevice.value != null) return false
        val address = device.address ?: return false
        val savedAddresses = _savedDevices.value.map { it.address }.toSet()
        if (!savedAddresses.contains(address)) return false
        Timber.tag("HRM").i("auto-connect saved device: name=%s address=%s", device.name, address)
        selectedAddress = address
        connectToAddress(address)
        stopDiscovery()
        return true
    }

    private fun pruneDiscovered() {
        val savedAddresses = _savedDevices.value.map { it.address }.toSet()
        _discoveredDevices.value = _discoveredDevices.value.filter {
            it.address != selectedAddress && !savedAddresses.contains(it.address)
        }
    }

    private fun connectToAddress(address: String) {
        val context = appContext ?: return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            Timber.tag("HRM").i("connectToAddress: name=%s address=%s", device.name, address)
            connect(context, device)
        } catch (ex: IllegalArgumentException) {
            Timber.w(ex, "Invalid HR device address: %s", address)
        }
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
                    scheduleReconnect()
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Timber.tag("HRM").i("connected: name=%s address=%s", device.name, device.address)
                        lastConnectedAtMs = System.currentTimeMillis()
                        val name = device.name ?: prefs?.getString(PrefNamePrefix + device.address, null)
                        _connectedDevice.value = HeartRateDevice(device.address, name)
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Timber.tag("HRM").i("disconnected: name=%s address=%s", device.name, device.address)
                        try { gatt.close() } catch (_: Exception) {}
                        if (bluetoothGatt === gatt) bluetoothGatt = null
                        _heartRate.value = null
                        _connectedDevice.value = null
                        lastConnectedAtMs = 0L
                        scheduleReconnect()
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
                Timber.tag("HRM").i("notifications enabled")
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
                        lastHeartRateAtMs = System.currentTimeMillis()
                    }
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
        if (_savedDevices.value.isNotEmpty()) {
            Timber.tag("HRM").i("scheduleReconnect: scanning saved devices")
            startAutoReconnectScan()
            return
        }
        val target = selectedAddress ?: return
        Timber.tag("HRM").i("scheduleReconnect: address=%s", target)
        scope.launch {
            delay(ReconnectDelayMs)
            if (!stopped.get()) {
                connectToAddress(target)
            }
        }
    }

    private fun startAutoReconnectScan() {
        if (stopped.get()) return
        if (_isScanning.value) return
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            Timber.tag("HRM").i("auto-reconnect scan start")
            startDiscovery()
            delay(AutoReconnectScanMs)
            if (_isScanning.value && _connectedDevice.value == null) {
                Timber.tag("HRM").i("auto-reconnect scan timeout")
                stopDiscovery()
            }
        }
    }
}
