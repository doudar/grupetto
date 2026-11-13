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
import com.spop.poverlay.sensor.ScanBudget
import com.spop.poverlay.sensor.shouldDiscoverSensor
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
    private const val PrefZoneMax = "hr_zone_max"
    private const val PrefZone12 = "hr_zone_12"
    private const val PrefZone23 = "hr_zone_23"
    private const val PrefZone34 = "hr_zone_34"
    private const val PrefZone45 = "hr_zone_45"
    private const val PrefMatchByName = "hr_match_by_name"

    private const val ReconnectDelayMs = 1_000L
    private const val DirectRetryMs = 2_000L
    // Each background scan runs this long at full speed, then restarts after ReconnectDelayMs.
    // Long enough to catch a strap that advertises slowly, short enough that Android never
    // demotes it to an opportunistic scan.
    private const val AutoReconnectScanMs = 30_000L
    private const val StaleHeartRateTimeoutMs = 12_000L
    // A strap that has just connected can take a while to find a pulse (dry electrodes, cold hands)
    // before it sends its first reading; give it longer than an established connection.
    private const val FirstReadingTimeoutMs = 30_000L
    // Android abandons a direct connectGatt after about 30 s; do not restart a
    // pending attempt to the same strap sooner. Straps already serving other
    // receivers can take several seconds to accept, and every restart cancels
    // the attempt in progress.
    private const val PendingConnectTimeoutMs = 30_000L

    // Default zones based on 180 bpm max HR (typical 40-year-old), 10% bands
    private const val DefaultZoneMax = 180
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

    private val _connectingDevice = MutableStateFlow<HeartRateDevice?>(null)
    val connectingDevice: StateFlow<HeartRateDevice?> = _connectingDevice

    private val _status = MutableStateFlow("No heart-rate monitor selected")
    val status: StateFlow<String> = _status
    private fun deviceName(address: String) = _savedDevices.value.firstOrNull { it.address == address }?.name
        ?: prefs?.getString(PrefNamePrefix + address, null) ?: address

    private val _discoveredDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<HeartRateDevice>> = _discoveredDevices

    private val _savedDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val savedDevices: StateFlow<List<HeartRateDevice>> = _savedDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _zoneMax = MutableStateFlow<Int?>(null)
    /** The top of zone 5: the heart rate that counts as 100%. */
    val zoneMax: StateFlow<Int?> = _zoneMax
    private val _zone12 = MutableStateFlow<Int?>(null)
    private val _zone23 = MutableStateFlow<Int?>(null)
    private val _zone34 = MutableStateFlow<Int?>(null)
    private val _zone45 = MutableStateFlow<Int?>(null)
    val zone12: StateFlow<Int?> = _zone12
    val zone23: StateFlow<Int?> = _zone23
    val zone34: StateFlow<Int?> = _zone34
    val zone45: StateFlow<Int?> = _zone45
    // Zones belong to the selected monitor, so each rider's strap uses that rider's zones.
    private val _zoneOwner = MutableStateFlow<String?>(null)
    val zoneOwner: StateFlow<String?> = _zoneOwner
    private val _heartRateZones = MutableStateFlow<List<Int>?>(null)
    val heartRateZones: StateFlow<List<Int>?> = _heartRateZones

    private val _matchByName = MutableStateFlow(false)
    val matchByName: StateFlow<Boolean> = _matchByName

    @Volatile
    private var bluetoothGatt: BluetoothGatt? = null
    private var pendingAddress: String? = null
    private var pendingSinceMs = 0L

    private fun connectionPending(address: String) =
        bluetoothGatt != null && pendingAddress == address &&
            (pendingAutoConnect || monotonicTimeMs() - pendingSinceMs < PendingConnectTimeoutMs)

    @Volatile
    private var discoveryCallback: ScanCallback? = null

    @Volatile
    private var appContext: Context? = null

    private var bluetoothAccess: HeartRateBluetoothAccess? = null

    private var prefs: SharedPreferences? = null
    private var selectedAddress: String? = null
    private val stopped = AtomicBoolean(true)
    private var autoReconnectJob: kotlinx.coroutines.Job? = null
    private var nextScanAtMs = 0L
    private var scanStartedAtMs = 0L
    private var isManaging = false
    @Volatile
    private var manualDisconnectRequested = false

    private val scanBudget = ScanBudget()

    // True while the pending connection is an Android background connect (autoConnect). Android
    // keeps it open until the strap appears, so it needs no timeout and no app-side scanning. The
    // bike refuses scan results to the overlay when it is in the background, which made scanning
    // for a saved strap unreliable.
    @Volatile
    private var pendingAutoConnect = false
    private var nextDirectConnectAtMs = 0L

    @Volatile
    private var lastHeartRateAtMs: Long = 0L

    @Volatile
    private var lastConnectedAtMs: Long = 0L

    private fun monotonicTimeMs() = System.nanoTime() / 1_000_000L

    // Release builds plant no Timber tree, and this bike's log drops everything below warning level,
    // so log at warning level. Capture with: adb logcat -v time | grep HeartRate
    private fun hrLog(message: String) {
        try {
            android.util.Log.w("HeartRate", message)
        } catch (_: RuntimeException) {
            // Not running on Android (unit tests).
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start(context: Context) {
        try {
            if (appContext != null && !stopped.get()) return
            if (bluetoothGatt != null) releaseCurrentGatt()
            appContext = context.applicationContext
            bluetoothAccess = HeartRateBluetoothAccess(context.applicationContext)
            prefs = appContext?.getSharedPreferences(PrefsName, Context.MODE_PRIVATE)
            _matchByName.value = prefs?.getBoolean(PrefMatchByName, false) ?: false
            stopped.set(false)
            loadSavedDevices()
            selectedAddress = prefs?.getString(PrefSelectedDevice, null)
            loadHeartRateZones()
            scanBudget.arm(monotonicTimeMs())
            hrLog("start: saved=${_savedDevices.value.size} selected=$selectedAddress manual=$manualDisconnectRequested")
            if (!manualDisconnectRequested) {
                selectedAddress?.let { connectToAddress(it) }
            }
            startAutoReconnectScan()
        } catch (ex: Exception) {
            Timber.w(ex, "HeartRateManager start failed")
        }
    }

    @Synchronized
    fun stop() {
        stopped.set(true)
        autoReconnectJob?.cancel()
        autoReconnectJob = null
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

    @Synchronized
    private fun releaseCurrentGatt() {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        pendingAddress = null
        pendingAutoConnect = false
        _connectingDevice.value = null
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

    private fun zoneKey(base: String, address: String?) = if (address == null) base else "${base}_$address"

    // A monitor's own zones win, then the shared default zones, then the built-in defaults.
    private fun readZone(base: String, builtIn: Int): Int =
        readIntOrNull(zoneKey(base, selectedAddress)) ?: readIntOrNull(base) ?: builtIn

    private fun loadHeartRateZones() {
        _zoneOwner.value = selectedAddress
        _zoneMax.value = readZone(PrefZoneMax, DefaultZoneMax)
        _zone12.value = readZone(PrefZone12, DefaultZone12)
        _zone23.value = readZone(PrefZone23, DefaultZone23)
        _zone34.value = readZone(PrefZone34, DefaultZone34)
        _zone45.value = readZone(PrefZone45, DefaultZone45)
        updateHeartRateZones()
    }

    /** Saves zones for the selected monitor, or as the shared default when none is selected. */
    fun setHeartRateZones(zone12: Int?, zone23: Int?, zone34: Int?, zone45: Int?, zoneMax: Int?) {
        val owner = selectedAddress
        prefs?.edit {
            if (zoneMax != null) putInt(zoneKey(PrefZoneMax, owner), zoneMax) else remove(zoneKey(PrefZoneMax, owner))
            if (zone12 != null) putInt(zoneKey(PrefZone12, owner), zone12) else remove(zoneKey(PrefZone12, owner))
            if (zone23 != null) putInt(zoneKey(PrefZone23, owner), zone23) else remove(zoneKey(PrefZone23, owner))
            if (zone34 != null) putInt(zoneKey(PrefZone34, owner), zone34) else remove(zoneKey(PrefZone34, owner))
            if (zone45 != null) putInt(zoneKey(PrefZone45, owner), zone45) else remove(zoneKey(PrefZone45, owner))
        }
        _zoneMax.value = zoneMax
        _zone12.value = zone12
        _zone23.value = zone23
        _zone34.value = zone34
        _zone45.value = zone45
        updateHeartRateZones()
    }

    private fun copyZones(from: String, to: String) {
        val p = prefs ?: return
        p.edit {
            for (base in listOf(PrefZone12, PrefZone23, PrefZone34, PrefZone45, PrefZoneMax)) {
                val key = zoneKey(base, from)
                if (p.contains(key)) putInt(zoneKey(base, to), p.getInt(key, 0))
                remove(key)
            }
        }
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

    // Always scan at LOW_LATENCY. The background auto-reconnect used LOW_POWER (about a 10% duty
    // cycle), which on the bike's tablet often missed a saved strap until settings opened the fast
    // scan. The bike is mains powered, so the extra power draw does not matter.
    @Synchronized
    fun startDiscovery(scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY) {
        if (!shouldScan()) { stopDiscovery(); updateIdleStatus(); return }
        val access = bluetoothAccess ?: return
        if (!access.canScan()) {
            stopDiscovery()
            if (bluetoothGatt == null) _status.value = "Bluetooth and location permissions required"
            return
        }
        if (_isScanning.value) return

        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(this@HeartRateManager) {
                    if (discoveryCallback !== this) return
                    val device = result.device ?: return
                    val hasHrService = result.scanRecord?.serviceUuids?.any { it.uuid == HR_SERVICE } == true
                    if (!hasHrService) return
                    val info = access.deviceInfo(device) ?: return
                    if (maybeAutoConnectSaved(info)) {
                        hrLog("scan hit saved strap ${info.address}, connecting")
                        return
                    }
                    addDiscoveredDevice(info)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                synchronized(this@HeartRateManager) {
                    if (discoveryCallback !== this) return
                    discoveryCallback = null
                    hrLog("scan failed code=$errorCode")
                    nextScanAtMs = monotonicTimeMs() + ReconnectDelayMs
                    _isScanning.value = false
                    if (bluetoothGatt == null) _status.value = "Heart-rate scan failed ($errorCode) · retrying"
                    Timber.w("HR scan failed: %s", errorCode)
                }
            }
        }

        scanStartedAtMs = monotonicTimeMs()
        hrLog("scan start mode=$scanMode")
        discoveryCallback = callback
        _isScanning.value = true
        if (bluetoothGatt == null) _status.value = selectedAddress?.let { "Searching for ${deviceName(it)}" }
            ?: "Searching for heart-rate monitors"
        if (!access.startScan(listOf(filter), settings, callback)) {
            discoveryCallback = null
            nextScanAtMs = monotonicTimeMs() + ReconnectDelayMs
            _isScanning.value = false
            if (bluetoothGatt == null) _status.value = "Bluetooth scan unavailable · retrying"
        }
    }

    @Synchronized
    fun stopDiscovery() {
        val callback = discoveryCallback
        discoveryCallback = null
        _isScanning.value = false
        if (callback != null) bluetoothAccess?.stopScan(callback)
    }

    /**
     * Reports whether the bike is moving. When it starts after sitting idle, open a new five-minute
     * window of full-speed scanning for a saved strap. A pending connection that went nowhere is
     * dropped so the scan can run. Does nothing after a manual disconnect, with no saved strap, or
     * while a strap is connected.
     */
    @Synchronized
    fun onBikeMovement(moving: Boolean) {
        if (!scanBudget.onMovement(moving, monotonicTimeMs())) return
        hrLog("bike moving after idle: new scan window, connected=${_connectedDevice.value != null} gatt=${bluetoothGatt != null}")
        if (stopped.get() || manualDisconnectRequested || _connectedDevice.value != null) return
        if (_savedDevices.value.isEmpty()) return
        if (bluetoothGatt != null && !pendingAutoConnect) releaseCurrentGatt()
        stopDiscovery()
        nextScanAtMs = 0L
        startDiscovery(ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    /** The matching pairing picker is visible, not merely the settings screen. */
    @Synchronized
    fun setManaging(active: Boolean) {
        if (isManaging == active) return
        isManaging = active
        stopDiscovery()
        if (shouldScan()) startDiscovery(ScanSettings.SCAN_MODE_LOW_LATENCY)
        else updateIdleStatus()
    }

    private fun shouldScan() = shouldDiscoverSensor(
        running = !stopped.get(), pickerOpen = isManaging,
        hasSavedTarget = _savedDevices.value.isNotEmpty() && scanBudget.allows(monotonicTimeMs()),
        manuallyDisconnected = manualDisconnectRequested,
        hasGatt = bluetoothGatt != null && !pendingAutoConnect
    )

    @Synchronized
    fun connectTo(device: HeartRateDevice) {
        // A stale picker tap must not replace the sensor already owning this type.
        if (bluetoothGatt != null) return
        manualDisconnectRequested = false
        saveDevice(device)
        selectedAddress = device.address
        loadHeartRateZones()
        scanBudget.arm(monotonicTimeMs())
        // Further scan results would otherwise restart this connection attempt.
        stopDiscovery()
        connectToAddress(device.address)
    }

    @Synchronized
    fun disconnectCurrent() {
        manualDisconnectRequested = true
        stopDiscovery()
        selectedAddress = null
        loadHeartRateZones()
        prefs?.edit { remove(PrefSelectedDevice) }
        releaseCurrentGatt()
        _status.value = "Heart-rate monitor disconnected"
    }

    @Synchronized
    fun forgetDevice(address: String) {
        val p = prefs ?: return
        val saved = p.getStringSet(PrefSavedDevices, emptySet()).orEmpty().toMutableSet()
        saved.remove(address)
        p.edit {
            putStringSet(PrefSavedDevices, saved)
            remove(PrefNamePrefix + address)
            for (base in listOf(PrefZone12, PrefZone23, PrefZone34, PrefZone45, PrefZoneMax)) remove(zoneKey(base, address))
            if (selectedAddress == address) remove(PrefSelectedDevice)
        }
        if (selectedAddress == address) {
            selectedAddress = null
            loadHeartRateZones()
            releaseCurrentGatt()
            _status.value = "No heart-rate monitor selected"
        }
        loadSavedDevices()
        pruneDiscovered()
        if (!shouldScan()) { stopDiscovery(); updateIdleStatus() }
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

    @Synchronized
    private fun maybeAutoConnectSaved(device: HeartRateDevice): Boolean {
        if (manualDisconnectRequested) return false
        // A background connect to the last-used strap may be waiting; a scan hit on another saved
        // strap (a second rider) takes over from it.
        if (bluetoothGatt != null && !pendingAutoConnect) return false
        val address = device.address
        // Exact MAC match
        if (_savedDevices.value.any { it.address == address }) {
            selectedAddress = address
            loadHeartRateZones()
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
            loadHeartRateZones()
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
        copyZones(oldAddress, newAddress)
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

    @Synchronized
    private fun connectToAddress(address: String) {
        val access = bluetoothAccess ?: return
        if (connectionPending(address) || _connectedDevice.value?.address == address) return
        stopDiscovery()
        releaseCurrentGatt()
        if (!access.canConnect()) { _status.value = "Bluetooth permission required"; return }
        val device = access.remoteDevice(address)
        if (device == null) { _status.value = "${deviceName(address)} unavailable · retrying"; return }
        pendingAddress = address
        pendingAutoConnect = !_matchByName.value && _savedDevices.value.any { it.address == address }
        hrLog("connect $address autoConnect=$pendingAutoConnect")
        _connectingDevice.value = HeartRateDevice(address, deviceName(address))
        pendingSinceMs = monotonicTimeMs()
        _status.value = "Connecting to ${deviceName(address)}"
        bluetoothGatt = access.connect(device, autoConnect = pendingAutoConnect, callback = object : BluetoothGattCallback() {
            private var lowPowerRequested = false

            private fun connectionFailed(gatt: BluetoothGatt) {
                if (bluetoothGatt !== gatt) {
                    access.close(gatt)
                    return
                }
                hrLog("connection failed/lost (connected=${_connectedDevice.value != null})")
                releaseCurrentGatt()
                // A strap that is there but failed to connect or dropped gets a fresh five minutes.
                scanBudget.arm(monotonicTimeMs())
                nextDirectConnectAtMs = monotonicTimeMs() + DirectRetryMs
                _status.value = "Reconnecting to ${deviceName(address)}"
                scheduleReconnect()
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                synchronized(this@HeartRateManager) {
                    hrLog("gatt state status=$status newState=$newState current=${bluetoothGatt === gatt}")
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
                            stopDiscovery()
                            pendingAddress = null
                            pendingAutoConnect = false
                            _connectingDevice.value = null
                            lastConnectedAtMs = monotonicTimeMs()
                            val name = info.name ?: prefs?.getString(PrefNamePrefix + info.address, null)
                            _connectedDevice.value = info.copy(name = name)
                            _status.value = "Connected · waiting for heart rate"
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            connectionFailed(gatt)
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                synchronized(this@HeartRateManager) {
                    if (bluetoothGatt !== gatt) return
                    hrLog("services discovered status=$status hrService=${gatt.getService(HR_SERVICE) != null}")
                    if (status != BluetoothGatt.GATT_SUCCESS) { connectionFailed(gatt); return }
                    val desc = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)?.getDescriptor(CCC_UUID)
                    val characteristic = gatt.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
                    if (desc == null || characteristic == null) { connectionFailed(gatt); return }
                    if (!access.enableNotifications(gatt, characteristic, desc)) connectionFailed(gatt)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: android.bluetooth.BluetoothGattDescriptor,
                status: Int
            ) {
                synchronized(this@HeartRateManager) {
                    if (bluetoothGatt !== gatt || descriptor.uuid != CCC_UUID) return
                    hrLog("notifications enabled status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        connectionFailed(gatt)
                        return
                    }
                    if (!lowPowerRequested) {
                        lowPowerRequested = true
                        access.requestLowPowerConnection(gatt)
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                synchronized(this@HeartRateManager) {
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
                        if (_heartRate.value == null) hrLog("first heart rate reading: $bpm")
                        _heartRate.value = bpm
                        lastHeartRateAtMs = monotonicTimeMs()
                        _status.value = "Heart rate active"
                    }
                }
            }
        })
        if (bluetoothGatt == null) {
            pendingAddress = null
            _connectingDevice.value = null
            _status.value = "Connection to ${deviceName(address)} failed · retrying"
        }
    }

    @Synchronized
    private fun scheduleReconnect() {
        nextScanAtMs = monotonicTimeMs() + ReconnectDelayMs
        startAutoReconnectScan()
    }

    private fun startAutoReconnectScan() {
        if (stopped.get() || autoReconnectJob?.isActive == true) return
        autoReconnectJob = scope.launch {
            while (!stopped.get()) {
                checkConnection()
                delay(1_000L)
            }
        }
    }

    private fun updateIdleStatus() {
        if (bluetoothGatt == null && !isManaging &&
            (manualDisconnectRequested || _savedDevices.value.isEmpty())) {
            _status.value = if (manualDisconnectRequested) "Heart-rate monitor disconnected"
                else "No heart-rate monitor selected"
        } else if (bluetoothGatt == null && !isManaging && !manualDisconnectRequested &&
            _savedDevices.value.isNotEmpty() && !scanBudget.allows(monotonicTimeMs())) {
            _status.value = "Saved heart-rate monitor not found · will search again next ride"
        }
    }

    @Synchronized
    internal fun checkConnection() {
        if (stopped.get()) return
        val now = monotonicTimeMs()
        if (bluetoothAccess?.canConnect() != true) {
            releaseCurrentGatt()
            stopDiscovery()
            _status.value = "Bluetooth permission required"
            return
        }
        if (bluetoothGatt != null) {
            val connected = _connectedDevice.value != null
            val lastSignal = if (connected) maxOf(lastHeartRateAtMs, lastConnectedAtMs) else pendingSinceMs
            val timeout = when {
                !connected && pendingAutoConnect -> Long.MAX_VALUE
                !connected -> PendingConnectTimeoutMs
                lastHeartRateAtMs == 0L -> FirstReadingTimeoutMs
                else -> StaleHeartRateTimeoutMs
            }
            if (now - lastSignal > timeout) {
                hrLog("timeout: connected=$connected firstReading=${lastHeartRateAtMs != 0L}")
                releaseCurrentGatt()
                if (connected) scanBudget.arm(now)
                nextScanAtMs = now + ReconnectDelayMs
                nextDirectConnectAtMs = now + DirectRetryMs
                _status.value = "Heart-rate data lost · reconnecting"
            }
        }
        // Wait for the last-used saved strap with a background connect: no scan permission needed.
        val target = selectedAddress
        if (bluetoothGatt == null && target != null && !manualDisconnectRequested && !_matchByName.value &&
            _savedDevices.value.any { it.address == target } && now >= nextDirectConnectAtMs) {
            connectToAddress(target)
        }
        if (!shouldScan()) { stopDiscovery(); updateIdleStatus(); return }
        if (_isScanning.value && now - scanStartedAtMs >= AutoReconnectScanMs) {
            stopDiscovery()
            nextScanAtMs = now + ReconnectDelayMs
        } else if (!_isScanning.value && now >= nextScanAtMs) {
            startDiscovery(ScanSettings.SCAN_MODE_LOW_LATENCY)
        }
    }
}
