package com.spop.poverlay.sensor.heartrate

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class HeartRateDevice(
    val address: String,
    val name: String?,
)

object HeartRateManager {
    private const val PrefsName = "heart_rate"
    private const val PrefSavedDevices = "hr_saved_devices"
    private const val PrefSelectedDevice = "hr_selected_device"
    private const val PrefNamePrefix = "hr_name_"
    private const val PrefZone12 = "hr_zone_12"
    private const val PrefZone23 = "hr_zone_23"
    private const val PrefZone34 = "hr_zone_34"
    private const val PrefZone45 = "hr_zone_45"
    private const val PrefMatchByName = "hr_match_by_name"

    private const val ReconnectDelayMs = 3_000L
    private const val AutoReconnectScanMs = 10_000L
    private const val StaleHeartRateTimeoutMs = 12_000L

    // Default zones based on 180 bpm max HR (typical 40-year-old), 10% bands
    private const val DefaultZone12 = 108
    private const val DefaultZone23 = 126
    private const val DefaultZone34 = 144
    private const val DefaultZone45 = 162

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

    private val _zone12 = MutableStateFlow<Int?>(null)
    private val _zone23 = MutableStateFlow<Int?>(null)
    private val _zone34 = MutableStateFlow<Int?>(null)
    private val _zone45 = MutableStateFlow<Int?>(null)
    val zone12: StateFlow<Int?> = _zone12
    val zone23: StateFlow<Int?> = _zone23
    val zone34: StateFlow<Int?> = _zone34
    val zone45: StateFlow<Int?> = _zone45
    private val _heartRateZones = MutableStateFlow<List<Int>?>(null)
    val heartRateZones: StateFlow<List<Int>?> = _heartRateZones

    private val _matchByName = MutableStateFlow(false)
    val matchByName: StateFlow<Boolean> = _matchByName

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null

    @Volatile
    private var discoveryCallback: ScanCallback? = null

    @Volatile
    private var appContext: Context? = null

    private var bluetoothAccess: HeartRateBluetoothAccess? = null

    private var prefs: SharedPreferences? = null
    private var selectedAddress: String? = null
    private val stopped = AtomicBoolean(true)
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var manageSessionJob: kotlinx.coroutines.Job? = null
    private var isManaging = false
    @Volatile
    private var manualDisconnectRequested = false

    @Volatile
    private var lastHeartRateAtMs: Long = 0L

    @Volatile
    private var lastConnectedAtMs: Long = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start(context: Context) {
        try {
            if (appContext != null && bluetoothGatt != null && bluetoothAccess?.canConnect() == true) return
            if (bluetoothGatt != null) releaseCurrentGatt()
            appContext = context.applicationContext
            bluetoothAccess = HeartRateBluetoothAccess(context.applicationContext)
            prefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            loadHeartRateZones()
            _matchByName.value = prefs?.getBoolean(PrefMatchByName, false) ?: false
            stopped.set(false)
            loadSavedDevices()
            selectedAddress = prefs?.getString(PrefSelectedDevice, null)
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
        releaseCurrentGatt()
    }

    private fun clearConnectionState() {
        _heartRate.value = null
        _connectedDevice.value = null
        lastHeartRateAtMs = 0L
        lastConnectedAtMs = 0L
    }

    private fun releaseCurrentGatt() {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        clearConnectionState()
        if (gatt != null) {
            bluetoothAccess?.disconnect(gatt)
            // A denied disconnect must not prevent the separate close attempt.
            bluetoothAccess?.close(gatt)
        }
    }

    private fun readIntOrNull(key: String): Int? {
        val sharedPrefs = prefs ?: return null
        return if (sharedPrefs.contains(key)) sharedPrefs.getInt(key, 0) else null
    }

    private fun loadHeartRateZones() {
        _zone12.value = readIntOrNull(PrefZone12) ?: DefaultZone12
        _zone23.value = readIntOrNull(PrefZone23) ?: DefaultZone23
        _zone34.value = readIntOrNull(PrefZone34) ?: DefaultZone34
        _zone45.value = readIntOrNull(PrefZone45) ?: DefaultZone45
        updateHeartRateZones()
    }

    fun setHeartRateZones(zone12: Int?, zone23: Int?, zone34: Int?, zone45: Int?) {
        prefs?.edit {
            if (zone12 != null) putInt(PrefZone12, zone12) else remove(PrefZone12)
            if (zone23 != null) putInt(PrefZone23, zone23) else remove(PrefZone23)
            if (zone34 != null) putInt(PrefZone34, zone34) else remove(PrefZone34)
            if (zone45 != null) putInt(PrefZone45, zone45) else remove(PrefZone45)
        }
        _zone12.value = zone12
        _zone23.value = zone23
        _zone34.value = zone34
        _zone45.value = zone45
        updateHeartRateZones()
    }

    fun setMatchByName(enabled: Boolean) {
        _matchByName.value = enabled
        prefs?.edit { putBoolean(PrefMatchByName, enabled) }
    }

    private fun updateHeartRateZones() {
        val z12 = _zone12.value
        val z23 = _zone23.value
        val z34 = _zone34.value
        val z45 = _zone45.value
        _heartRateZones.value = if (
            z12 != null && z23 != null && z34 != null && z45 != null &&
                z12 > 0 && z12 < z23 && z23 < z34 && z34 < z45
        ) {
            listOf(z12, z23, z34, z45)
        } else {
            null
        }
    }

    // scanMode defaults to LOW_LATENCY for the user-initiated "scan for devices" flow, which
    // wants fast results. The unattended background auto-reconnect loop passes LOW_POWER
    // instead, since it can run continuously (whenever no strap is connected) and fast
    // discovery there isn't worth the extra battery/CPU draw.
    fun startDiscovery(scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY) {
        val access = bluetoothAccess ?: return
        if (!access.canScan()) {
            stopDiscovery()
            return
        }
        if (_isScanning.value) return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (discoveryCallback !== this) return
                val device = result.device ?: return
                val hasHrService = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
                if (!hasHrService) return
                val info = access.deviceInfo(device) ?: return
                if (maybeAutoConnectSaved(info)) return
                addDiscoveredDevice(info)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (discoveryCallback !== this) return
                discoveryCallback = null
                _isScanning.value = false
                Timber.w("HR scan failed: %s", errorCode)
            }
        }

        discoveryCallback = callback
        _isScanning.value = true
        if (!access.startScan(listOf(filter), settings, callback)) {
            discoveryCallback = null
            _isScanning.value = false
        }
    }

    fun stopDiscovery() {
        val callback = discoveryCallback
        discoveryCallback = null
        _isScanning.value = false
        if (callback != null) bluetoothAccess?.stopScan(callback)
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
                    if (lastSignalAtMs > 0) {
                        val ageMs = System.currentTimeMillis() - lastSignalAtMs
                        if (ageMs > StaleHeartRateTimeoutMs) {
                            releaseCurrentGatt()
                            scheduleReconnect()
                        }
                    }
                }
                delay(1_000L)
            }
        }
    }

    fun connectTo(device: HeartRateDevice) {
        manualDisconnectRequested = false
        saveDevice(device)
        selectedAddress = device.address
        connectToAddress(device.address)
    }

    fun disconnectCurrent() {
        manualDisconnectRequested = true
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        stopDiscovery()
        selectedAddress = null
        releaseCurrentGatt()
    }

    fun forgetDevice(address: String) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(address)
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + address)
            if (selectedAddress == address) remove(PrefSelectedDevice)
        }
        if (selectedAddress == address) {
            selectedAddress = null
            releaseCurrentGatt()
        }
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun saveDevice(device: HeartRateDevice) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.add(device.address)
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            putString(PrefSelectedDevice, device.address)
            if (!device.name.isNullOrBlank()) putString(PrefNamePrefix + device.address, device.name)
        }
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun loadSavedDevices() {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty()
        _savedDevices.value = saved.map { HeartRateDevice(it, p.getString(PrefNamePrefix + it, null)) }
            .sortedBy { it.name ?: it.address }
    }

    private fun addDiscoveredDevice(device: HeartRateDevice) {
        val address = device.address
        if (address == selectedAddress) return
        if (_savedDevices.value.any { it.address == address }) return
        val current = _discoveredDevices.value.toMutableList()
        if (current.none { it.address == address }) {
            current.add(device)
            _discoveredDevices.value = current.sortedBy { it.name ?: it.address }
        }
    }

    private fun maybeAutoConnectSaved(device: HeartRateDevice): Boolean {
        if (manualDisconnectRequested) return false
        if (_connectedDevice.value != null) return false
        val address = device.address

        // Exact MAC match
        if (_savedDevices.value.any { it.address == address }) {
            selectedAddress = address
            connectToAddress(address)
            stopDiscovery()
            return true
        }

        // Name-only match (handles randomized/changed MAC addresses)
        if (_matchByName.value) {
            val deviceName = device.name?.takeIf { it.isNotBlank() } ?: return false
            val matched = _savedDevices.value.firstOrNull {
                !it.name.isNullOrBlank() && it.name == deviceName
            } ?: return false
            updateSavedDeviceAddress(oldAddress = matched.address, newAddress = address, name = deviceName)
            selectedAddress = address
            connectToAddress(address)
            stopDiscovery()
            return true
        }

        return false
    }

    private fun updateSavedDeviceAddress(oldAddress: String, newAddress: String, name: String) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(oldAddress)
        saved.add(newAddress)
        val wasSelected = p.getString(PrefSelectedDevice, null) == oldAddress
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + oldAddress)
            putString(PrefNamePrefix + newAddress, name)
            if (wasSelected) putString(PrefSelectedDevice, newAddress)
        }
        Timber.i("HR device name-matched '%s': updated address %s → %s", name, oldAddress, newAddress)
        loadSavedDevices()
        pruneDiscovered()
    }

    private fun pruneDiscovered() {
        val savedAddresses = _savedDevices.value.map { it.address }.toSet()
        _discoveredDevices.value = _discoveredDevices.value.filter {
            it.address != selectedAddress && !savedAddresses.contains(it.address)
        }
    }

    private fun connectToAddress(address: String) {
        val access = bluetoothAccess ?: return
        releaseCurrentGatt()
        val device = access.remoteDevice(address) ?: return
        bluetoothGatt = access.connect(device, object : BluetoothGattCallback() {
            private var lowPowerRequested = false

            private fun connectionFailed(gatt: BluetoothGatt) {
                if (bluetoothGatt !== gatt) {
                    access.close(gatt)
                    return
                }
                releaseCurrentGatt()
                scheduleReconnect()
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (bluetoothGatt !== gatt) {
                    access.close(gatt)
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectionFailed(gatt)
                    return
                }
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        val info = access.deviceInfo(device)
                        if (info == null || !access.discoverServices(gatt)) {
                            connectionFailed(gatt)
                            return
                        }
                        lastConnectedAtMs = System.currentTimeMillis()
                        val name = info.name ?: prefs?.getString(PrefNamePrefix + info.address, null)
                        _connectedDevice.value = info.copy(name = name)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectionFailed(gatt)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (bluetoothGatt !== gatt) return
                if (status != BluetoothGatt.GATT_SUCCESS) return
                val service = gatt.getService(HR_SERVICE) ?: return
                val characteristic = service.getCharacteristic(HR_MEASUREMENT) ?: return
                val desc = characteristic.getDescriptor(CCC_UUID) ?: return
                if (!access.enableNotifications(gatt, characteristic, desc)) connectionFailed(gatt)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: android.bluetooth.BluetoothGattDescriptor,
                status: Int
            ) {
                if (bluetoothGatt !== gatt || descriptor.uuid != CCC_UUID) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectionFailed(gatt)
                    return
                }
                if (!lowPowerRequested) {
                    lowPowerRequested = true
                    access.requestLowPowerConnection(gatt)
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (bluetoothGatt !== gatt) return
                if (!access.canConnect()) {
                    connectionFailed(gatt)
                    return
                }
                if (characteristic.uuid != HR_MEASUREMENT) return
                val data = characteristic.value ?: return
                if (data.size < 2) return
                val flags = data[0].toInt()
                val format16 = flags and 0x01 != 0
                val bpm = if (format16) {
                    if (data.size >= 3) ((data[2].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else null
                } else {
                    data[1].toInt() and 0xFF
                }
                if (bpm != null && bpm > 0) {
                    _heartRate.value = bpm
                    lastHeartRateAtMs = System.currentTimeMillis()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped.get()) return
        if (manualDisconnectRequested) return
        if (_savedDevices.value.isNotEmpty()) {
            startAutoReconnectScan()
            return
        }
        val target = selectedAddress ?: return
        scope.launch {
            delay(ReconnectDelayMs)
            if (!stopped.get()) connectToAddress(target)
        }
    }

    private fun startAutoReconnectScan() {
        if (stopped.get()) return
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            while (!stopped.get() && _connectedDevice.value == null && !manualDisconnectRequested) {
                if (!_isScanning.value) {
                    startDiscovery(ScanSettings.SCAN_MODE_LOW_POWER)
                }
                delay(AutoReconnectScanMs)
                if (_connectedDevice.value == null && !stopped.get() && !manualDisconnectRequested) {
                    stopDiscovery()
                    delay(ReconnectDelayMs)
                }
            }
        }
    }
}
