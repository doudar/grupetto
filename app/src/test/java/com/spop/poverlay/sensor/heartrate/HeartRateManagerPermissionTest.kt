package com.spop.poverlay.sensor.heartrate

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class HeartRateManagerPermissionTest {
    private val context = mockk<Context>()
    private val adapter = mockk<BluetoothAdapter>()
    private val device = mockk<BluetoothDevice>()
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val callback = slot<BluetoothGattCallback>()
    private val measurement = mockk<BluetoothGattCharacteristic>()
    private var connectGranted = true
    private val address = "01:02:03:04:05:06"

    @Before fun setup() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, any()) } answers {
            if (secondArg<String>() == Manifest.permission.BLUETOOTH_CONNECT && !connectGranted)
                PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
        }
        every { adapter.getRemoteDevice(address) } returns device
        every { device.address } returns address
        every { device.name } returns "Strap"
        every { device.connectGatt(context, false, capture(callback)) } returns gatt
        every { gatt.discoverServices() } returns true
        every { measurement.uuid } returns UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        every { measurement.value } returns byteArrayOf(0, 120)
        HeartRateManager.stop()
        field("bluetoothAccess", HeartRateBluetoothAccess(context, 34) { adapter })
        field("prefs", null)
    }

    @After fun cleanup() {
        HeartRateManager.stop()
        field("bluetoothAccess", null)
        unmockkStatic(ContextCompat::class)
    }

    private fun field(name: String, value: Any?) {
        HeartRateManager::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(HeartRateManager, value)
        }
    }

    private fun connectAndReceive() {
        HeartRateManager.connectTo(HeartRateDevice(address, "Strap"))
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onCharacteristicChanged(gatt, measurement)
        assertEquals(120, HeartRateManager.heartRate.value)
        assertEquals(address, HeartRateManager.connectedDevice.value?.address)
    }

    @Test fun `revoked permission clears state even when disconnect and close are denied`() {
        connectAndReceive()
        connectGranted = false
        every { gatt.disconnect() } throws SecurityException()
        every { gatt.close() } throws SecurityException()
        callback.captured.onCharacteristicChanged(gatt, measurement)
        assertNull(HeartRateManager.heartRate.value)
        assertNull(HeartRateManager.connectedDevice.value)
        verify(exactly = 1) { gatt.disconnect(); gatt.close() }
    }

    @Test fun `revocation between connection and service discovery clears connection`() {
        HeartRateManager.connectTo(HeartRateDevice(address, "Strap"))
        every { gatt.discoverServices() } throws SecurityException()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertNull(HeartRateManager.heartRate.value)
        assertNull(HeartRateManager.connectedDevice.value)
        verify(exactly = 1) { gatt.close() }
    }

    @Test fun `revocation while enabling notifications clears established connection`() {
        connectAndReceive()
        val service = mockk<BluetoothGattService>()
        val descriptor = mockk<BluetoothGattDescriptor>()
        every { gatt.getService(any()) } returns service
        every { service.getCharacteristic(any()) } returns measurement
        every { measurement.getDescriptor(any()) } returns descriptor
        every { gatt.setCharacteristicNotification(measurement, true) } throws SecurityException()
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        assertNull(HeartRateManager.heartRate.value)
        assertNull(HeartRateManager.connectedDevice.value)
        verify(exactly = 1) { gatt.close() }
    }

    @Test fun `late callbacks after manual disconnect cannot restore stale values`() {
        connectAndReceive()
        HeartRateManager.disconnectCurrent()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onCharacteristicChanged(gatt, measurement)
        assertNull(HeartRateManager.heartRate.value)
        assertNull(HeartRateManager.connectedDevice.value)
        verify(exactly = 1) { gatt.discoverServices() }
    }

    @Test fun `denied connection grant avoids device access entirely`() {
        connectGranted = false
        HeartRateManager.connectTo(HeartRateDevice(address, "Strap"))
        assertNull(HeartRateManager.connectedDevice.value)
        verify { adapter wasNot Called; device wasNot Called }
    }

    @Test fun `scan cleanup clears scanning state even when stopScan is denied`() {
        val scanner = mockk<android.bluetooth.le.BluetoothLeScanner>()
        val scanCallback = mockk<ScanCallback>()
        every { adapter.bluetoothLeScanner } returns scanner
        every { scanner.stopScan(scanCallback) } throws SecurityException()
        field("discoveryCallback", scanCallback)
        HeartRateManager::class.java.getDeclaredField("_isScanning").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(HeartRateManager) as MutableStateFlow<Boolean>).value = true
        }
        HeartRateManager.stopDiscovery()
        assertFalse(HeartRateManager.isScanning.value)
        HeartRateManager.stopDiscovery()
        verify(exactly = 1) { scanner.stopScan(scanCallback) }
    }
}
