package com.spop.poverlay.sensor.power

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.ParcelUuid
import android.os.SystemClock
import com.spop.poverlay.sensor.heartrate.HeartRateBluetoothAccess
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Independent GATT connection: a power meter and an HR monitor can be used together. */
class PowerMeterManager internal constructor(
    context: Context,
    // The existing access layer is characteristic-agnostic and checks permissions per operation.
    private val access: HeartRateBluetoothAccess = HeartRateBluetoothAccess(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val log: (String) -> Unit = { android.util.Log.i("PowerMeter", it) },
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    companion object {
        val Service: UUID = UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")
        val Measurement: UUID = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
        val Cccd: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    private val preferences = context.getSharedPreferences("power_meter", Context.MODE_PRIVATE)
    private var started = false
    private var managing = false
    private var manuallyDisconnected = false
    private var gatt: BluetoothGatt? = null
    private var connecting: HeartRateDevice? = null
    private var connectionAt = 0L
    private var lastMeasurementAt: Long? = null
    private var loggedPacket = false
    private var lowPowerRequested = false
    private var scan: ScanCallback? = null
    private var watcher: Job? = null
    private var nextReconnectAt = 0L
    private var scanStartedAt = 0L
    private var selected = preferences.getString("selected", null)
    private val mutableDevices = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val discoveredDevices = mutableDevices.asStateFlow()
    private val mutableSaved = MutableStateFlow<List<HeartRateDevice>>(emptyList())
    val savedDevices = mutableSaved.asStateFlow()
    private val mutableConnected = MutableStateFlow<HeartRateDevice?>(null)
    val connectedDevice = mutableConnected.asStateFlow()
    private val mutableReading = MutableStateFlow<ExternalPowerReading?>(null)
    val reading = mutableReading.asStateFlow()
    private val mutableStatus = MutableStateFlow("No power meter selected")
    val status = mutableStatus.asStateFlow()
    private val mutableScanning = MutableStateFlow(false)
    val scanning = mutableScanning.asStateFlow()

    init { loadSaved() }
    @Synchronized fun start() {
        if (started) return
        started = true
        watcher = scope.launch {
            while (isActive) {
                checkConnection()
                delay(1000)
            }
        }
    }
    @Synchronized fun stop() {
        started = false
        watcher?.cancel(); watcher = null
        stopScan()
        release()
    }
    @Synchronized fun manage(enabled: Boolean) {
        managing = enabled
        if (enabled) { start(); startScan() } else stopScan()
    }
    @Synchronized internal fun checkConnection() {
        if (!started) return
        val now = clock()
        if (!access.canConnect()) {
            release(); stopScan(); mutableStatus.value = "Bluetooth permission required"
            return
        }
        val last = mutableReading.value
        if (last != null && freshExternalPower(last, now) == null) {
            mutableReading.value = null
            mutableStatus.value = "Power data lost · using bike meter"
        }
        if (gatt != null && now - (lastMeasurementAt ?: connectionAt) > 12000) {
            log("Data timeout: connected=${mutableConnected.value != null}, receivedPacket=$loggedPacket, lastValidAge=${lastMeasurementAt?.let { now - it }}")
            release(); nextReconnectAt = now + 3000
            mutableStatus.value = "Reconnecting to power meter"
        }
        if (gatt == null && !manuallyDisconnected && selected != null && now >= nextReconnectAt && scan == null) startScan()
        // Bound scan sessions; allow Android's scan throttling to settle between retries.
        if (scan != null && now - scanStartedAt > 15000) {
            stopScan(); nextReconnectAt = now + 5000
        } else if (managing && scan == null && now >= nextReconnectAt) startScan()
    }
    @Synchronized private fun startScan() {
        if (scan != null || !access.canScan()) {
            if (!access.canScan()) mutableStatus.value = "Bluetooth and location permissions required"
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(this@PowerMeterManager) {
                    if (scan !== this || !started) return
                    val info = access.deviceInfo(result.device) ?: return
                    if (info.name?.startsWith("Grupetto", ignoreCase = true) == true) return
                    mutableDevices.value = (mutableDevices.value.filterNot { it.address == info.address } + info)
                        .sortedBy { it.name ?: it.address }
                    if (gatt == null && !manuallyDisconnected && info.address == selected) connect(info)
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) {
                synchronized(this@PowerMeterManager) {
                    if (scan !== this) return
                    scan = null; mutableScanning.value = false
                    nextReconnectAt = clock() + 10000
                    mutableStatus.value = "Scan failed ($errorCode) · retrying"
                }
            }
        }
        scan = callback
        mutableScanning.value = true
        scanStartedAt = clock()
        if (!access.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(Service)).build()),
                ScanSettings.Builder().setScanMode(if (managing) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER).build(), callback)) {
            scan = null; mutableScanning.value = false
            mutableStatus.value = "Bluetooth scan unavailable"
            nextReconnectAt = clock() + 5000
        }
    }
    @Synchronized private fun stopScan() {
        val callback = scan
        scan = null; mutableScanning.value = false
        if (callback != null) access.stopScan(callback)
    }
    @Synchronized fun connect(device: HeartRateDevice) {
        start()
        manuallyDisconnected = false
        selected = device.address
        val addresses = preferences.getStringSet("saved", emptySet()).orEmpty() + device.address
        preferences.edit().putStringSet("saved", addresses).putString("selected", device.address)
            .putString("name_${device.address}", device.name).apply()
        loadSaved()
        stopScan(); release()
        val remote = access.remoteDevice(device.address)
        if (remote == null) { mutableStatus.value = "Power meter unavailable"; return }
        connecting = device
        connectionAt = clock()
        mutableStatus.value = "Connecting to ${device.name ?: device.address}"
        log("Connecting to selected power meter")
        gatt = access.connect(remote, callback)
        if (gatt == null) fail("Connection failed")
    }
    @Synchronized fun disconnect() {
        manuallyDisconnected = true
        selected = null
        preferences.edit().remove("selected").apply()
        stopScan(); release()
        mutableStatus.value = "Using bike meter"
    }
    @Synchronized fun forget(address: String) {
        if (selected == address || mutableConnected.value?.address == address) disconnect()
        preferences.edit().putStringSet("saved", preferences.getStringSet("saved", emptySet()).orEmpty() - address)
            .remove("name_$address").apply()
        loadSaved()
    }
    private fun loadSaved() {
        mutableSaved.value = preferences.getStringSet("saved", emptySet()).orEmpty()
            .map { HeartRateDevice(it, preferences.getString("name_$it", null)) }.sortedBy { it.name ?: it.address }
    }
    private fun release() {
        val old = gatt
        gatt = null; connecting = null
        lastMeasurementAt = null
        loggedPacket = false
        lowPowerRequested = false
        mutableConnected.value = null; mutableReading.value = null
        if (old != null) { access.disconnect(old); access.close(old) }
    }
    private fun fail(reason: String) {
        log(reason)
        release()
        mutableStatus.value = reason
        nextReconnectAt = clock() + 3000
    }
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            synchronized(this@PowerMeterManager) {
                if (gatt !== link) { access.close(link); return }
                log("Connection state: status=$status state=$newState")
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Power meter disconnected · using bike meter"); return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && !access.discoverServices(link)) fail("Service discovery failed")
            }
        }
        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            synchronized(this@PowerMeterManager) {
                if (gatt !== link) return
                val characteristic = link.getService(Service)?.getCharacteristic(Measurement)
                val descriptor = characteristic?.getDescriptor(Cccd)
                log("Services: status=$status measurement=${characteristic != null} cccd=${descriptor != null}")
                if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null || descriptor == null ||
                    !access.enableNotifications(link, characteristic, descriptor)) fail("Cycling Power notifications unavailable")
            }
        }
        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(this@PowerMeterManager) {
                if (gatt !== link || descriptor.uuid != Cccd) return
                log("Notification subscription: status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Power notification subscription failed"); return }
                if (!lowPowerRequested) {
                    lowPowerRequested = true
                    log("Low-power connection request accepted=${access.requestLowPowerConnection(link)}")
                }
                mutableConnected.value = connecting
                mutableStatus.value = "Connected · waiting for watts"
            }
        }
        @Deprecated("Android legacy callback")
        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            receive(link, characteristic.uuid, characteristic.value ?: return)
        }
        override fun onCharacteristicChanged(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            receive(link, characteristic.uuid, value)
        }
    }
    @Synchronized private fun receive(link: BluetoothGatt, uuid: UUID, bytes: ByteArray) {
        if (gatt !== link || uuid != Measurement) return
        if (!access.canConnect()) { fail("Bluetooth permission lost"); return }
        val watts = decodeCyclingPower(bytes)
        if (!loggedPacket) {
            log("First power packet: bytes=${bytes.size} data=${bytes.take(32).joinToString("") { "%02x".format(it.toInt() and 255) }} decoded=$watts")
            loggedPacket = true
        }
        if (watts == null) return
        val device = connecting ?: return
        mutableConnected.value = device
        val now = clock()
        lastMeasurementAt = now
        mutableReading.value = ExternalPowerReading(watts, now, device.address)
        mutableStatus.value = "External power active"
    }
}
