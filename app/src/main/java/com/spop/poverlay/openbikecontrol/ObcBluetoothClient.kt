package com.spop.poverlay.openbikecontrol

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import com.spop.poverlay.sensor.heartrate.HeartRateBluetoothAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

/**
 * Bluetooth transport: scans for the OpenBikeControl service and connects to the first
 * remote it sees. Scans are bounded and spaced out, because the Bike+ radio also
 * advertises Grupetto and may hold heart-rate and power-meter connections.
 */
internal class ObcBluetoothClient(
    private val access: HeartRateBluetoothAccess,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val appInfo: () -> ByteArray,
    private val onLink: (ObcLink) -> Unit,
    private val onMessage: (ObcMessage) -> Unit,
    private val scanRequest: () -> Pair<List<ScanFilter>, ScanSettings> = {
        listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(OpenBikeControl.Service)).build()) to
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
    }
) {
    private companion object {
        const val ScanMs = 15_000L
        const val ScanPauseMs = 15_000L
        const val RetryMs = 3_000L
        // Connect, MTU, discovery, subscription and App Info must finish within this.
        const val HandshakeMs = 30_000L
        // App Info is longer than the default 20-byte payload.
        const val Mtu = 64
    }
    private var running = false
    private var watcher: Job? = null
    private var scan: ScanCallback? = null
    private var scanStartedAt = 0L
    private var nextScanAt = 0L
    private var gatt: BluetoothGatt? = null
    private var device: String? = null
    private var servicesRequested = false
    private var searchPaused = false
    private var connectedAt: Long? = null
    private var connectStartedAt = 0L

    @Synchronized fun start() {
        if (running) return
        running = true
        nextScanAt = 0L
        watcher = scope.launch {
            while (isActive) { check(); delay(1000) }
        }
    }

    /** Stops scanning (a connected remote stays connected) until resumed. */
    @Synchronized fun pauseSearch(paused: Boolean) {
        if (searchPaused == paused) return
        searchPaused = paused
        if (!running || gatt != null) return
        if (paused) { stopScan(); onLink(ObcLink(ObcLink.Phase.Off, detail = "Not searching · Wi-Fi remote connected")) }
        else nextScanAt = 0L
    }

    @Synchronized fun stop() {
        running = false
        watcher?.cancel(); watcher = null
        stopScan(); release()
        onLink(ObcLink.Off)
    }

    @Synchronized internal fun check() {
        if (!running) return
        val now = clock()
        if (!access.canConnect()) {
            stopScan(); release()
            onLink(ObcLink(ObcLink.Phase.Unavailable, detail = "Bluetooth permission required"))
            return
        }
        if (gatt != null && connectedAt == null && now - connectStartedAt > HandshakeMs) fail("Connection timed out")
        if (gatt != null || searchPaused) return
        if (scan != null && now - scanStartedAt > ScanMs) { stopScan(); nextScanAt = now + ScanPauseMs }
        if (scan == null && now >= nextScanAt) startScan(now)
    }

    private fun startScan(now: Long) {
        if (!access.canScan()) {
            onLink(ObcLink(ObcLink.Phase.Unavailable, detail = "Bluetooth and location permissions required"))
            nextScanAt = now + ScanPauseMs
            return
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                synchronized(this@ObcBluetoothClient) {
                    if (scan !== this || !running || gatt != null) return
                    connect(result)
                }
            }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.firstOrNull()?.let { onScanResult(0, it) } }
            override fun onScanFailed(errorCode: Int) {
                synchronized(this@ObcBluetoothClient) {
                    if (scan !== this) return
                    scan = null
                    nextScanAt = clock() + ScanPauseMs
                    onLink(ObcLink(ObcLink.Phase.Unavailable, detail = "Bluetooth scan failed ($errorCode)"))
                }
            }
        }
        scan = callback
        scanStartedAt = now
        onLink(ObcLink(ObcLink.Phase.Searching))
        val (filters, settings) = scanRequest()
        if (!access.startScan(filters, settings, callback)) {
            scan = null
            nextScanAt = now + ScanPauseMs
            onLink(ObcLink(ObcLink.Phase.Unavailable, detail = "Bluetooth scan unavailable"))
        }
    }

    private fun stopScan() {
        val callback = scan ?: return
        scan = null
        access.stopScan(callback)
    }

    private fun connect(result: ScanResult) {
        stopScan()
        val info = access.deviceInfo(result.device) ?: return
        val name = result.scanRecord?.deviceName ?: info.name ?: info.address
        device = name
        servicesRequested = false
        connectedAt = null
        connectStartedAt = clock()
        onLink(ObcLink(ObcLink.Phase.Connecting, name))
        Timber.i("OpenBikeControl connecting to %s over Bluetooth", name)
        gatt = access.connect(result.device, callback)
        if (gatt == null) fail("Connection failed")
    }

    private fun release() {
        val old = gatt ?: return
        gatt = null
        connectedAt = null
        access.disconnect(old); access.close(old)
    }

    private fun fail(reason: String) {
        Timber.i("OpenBikeControl Bluetooth: %s", reason)
        val name = device
        release()
        nextScanAt = clock() + RetryMs
        if (running) onLink(ObcLink(ObcLink.Phase.Searching, name, "$reason · reconnecting"))
    }

    private fun connected() {
        connectedAt = clock()
        onLink(ObcLink(ObcLink.Phase.Connected, device))
    }

    private fun discoverServices(link: BluetoothGatt) {
        if (servicesRequested) return
        servicesRequested = true
        if (!access.discoverServices(link)) fail("Service discovery failed")
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(link: BluetoothGatt, status: Int, newState: Int) {
            synchronized(this@ObcBluetoothClient) {
                if (gatt !== link) { access.close(link); return }
                if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Disconnected"); return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    // Discovery waits for the MTU answer; without one, it starts anyway.
                    if (!access.requestMtu(link, Mtu)) discoverServices(link)
                }
            }
        }
        override fun onMtuChanged(link: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(this@ObcBluetoothClient) { if (gatt === link) discoverServices(link) }
        }
        override fun onServicesDiscovered(link: BluetoothGatt, status: Int) {
            synchronized(this@ObcBluetoothClient) {
                if (gatt !== link) return
                val buttons = link.getService(OpenBikeControl.Service)?.getCharacteristic(OpenBikeControl.ButtonState)
                val cccd = buttons?.getDescriptor(OpenBikeControl.Cccd)
                if (status != BluetoothGatt.GATT_SUCCESS || buttons == null || cccd == null ||
                    !access.enableNotifications(link, buttons, cccd)) fail("Not an OpenBikeControl remote")
            }
        }
        override fun onDescriptorWrite(link: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            synchronized(this@ObcBluetoothClient) {
                if (gatt !== link || descriptor.uuid != OpenBikeControl.Cccd) return
                if (status != BluetoothGatt.GATT_SUCCESS) { fail("Button notifications refused"); return }
                // BikeControl recognises the app only once App Info arrives, so the link
                // counts as connected after that write succeeds. App Info is optional for a
                // remote; without the characteristic, the subscription is enough.
                val info = link.getService(OpenBikeControl.Service)?.getCharacteristic(OpenBikeControl.AppInfo)
                if (info == null) connected()
                else if (!access.write(link, info, appInfo())) fail("App Info write failed")
            }
        }
        override fun onCharacteristicWrite(link: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            synchronized(this@ObcBluetoothClient) {
                if (gatt !== link || characteristic.uuid != OpenBikeControl.AppInfo) return
                if (status == BluetoothGatt.GATT_SUCCESS) connected() else fail("App Info refused ($status)")
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

    private fun receive(link: BluetoothGatt, uuid: UUID, value: ByteArray) {
        synchronized(this) { if (gatt !== link || uuid != OpenBikeControl.ButtonState) return }
        decodeObcMessage(value)?.let(onMessage)
    }
}
