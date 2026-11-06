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

    private fun subscriptionDescriptor(): BluetoothGattDescriptor = mockk<BluetoothGattDescriptor>().also {
        every { it.uuid } returns UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Test fun `sensor priority changes only after subscription and only once per connection`() {
        connectAndReceive()
        verify(exactly = 0) { gatt.requestConnectionPriority(any()) }
        val descriptor = subscriptionDescriptor()
        every { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) } returns true
        repeat(2) { callback.captured.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS) }
        verify(exactly = 1) { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
        HeartRateManager.disconnectCurrent()
        connectAndReceive()
        callback.captured.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 2) { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
    }

    @Test fun `rejected sensor priority keeps heart rate streaming`() {
        connectAndReceive()
        every { gatt.requestConnectionPriority(any()) } returns false
        callback.captured.onDescriptorWrite(gatt, subscriptionDescriptor(), BluetoothGatt.GATT_SUCCESS)
        every { measurement.value } returns byteArrayOf(0, 123)
        callback.captured.onCharacteristicChanged(gatt, measurement)
        assertEquals(123, HeartRateManager.heartRate.value)
        assertEquals(address, HeartRateManager.connectedDevice.value?.address)
        verify(exactly = 0) { gatt.disconnect(); gatt.close() }
    }

    @Test fun `failed subscription closes HR connection without changing priority`() {
        connectAndReceive()
        callback.captured.onDescriptorWrite(gatt, subscriptionDescriptor(), BluetoothGatt.GATT_FAILURE)
        assertNull(HeartRateManager.connectedDevice.value)
        verify(exactly = 0) { gatt.requestConnectionPriority(any()) }
        verify(exactly = 1) { gatt.close() }
    }

    @Test fun `late subscription callback cannot change priority after disconnect`() {
        connectAndReceive()
        HeartRateManager.disconnectCurrent()
        callback.captured.onDescriptorWrite(gatt, subscriptionDescriptor(), BluetoothGatt.GATT_SUCCESS)
        verify(exactly = 0) { gatt.requestConnectionPriority(any()) }
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
    @Test fun `pending identity clears on connect and cancel ignores late callback`() {
        HeartRateManager.connectTo(HeartRateDevice(address, "Strap"))
        assertEquals(address, HeartRateManager.connectingDevice.value?.address)
        HeartRateManager.disconnectCurrent()
        assertNull(HeartRateManager.connectingDevice.value)
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        assertNull(HeartRateManager.connectedDevice.value)
        connectAndReceive()
        assertNull(HeartRateManager.connectingDevice.value)
    }

    @Test fun `no saved HR monitor and closed picker never accesses scanner`() {
        HeartRateManager::class.java.getDeclaredField("stopped").apply {
            isAccessible = true
            (get(HeartRateManager) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        }
        field("isManaging", false)
        HeartRateManager::class.java.getDeclaredField("_savedDevices").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(HeartRateManager) as MutableStateFlow<List<HeartRateDevice>>).value = emptyList()
        }
        HeartRateManager.startDiscovery()
        HeartRateManager.checkConnection()
        assertFalse(HeartRateManager.isScanning.value)
        verify(exactly = 0) { adapter.bluetoothLeScanner }
    }

    @Test fun `opening picker cannot scan while a connection is pending or connected`() {
        HeartRateManager::class.java.getDeclaredField("stopped").apply {
            isAccessible = true
            (get(HeartRateManager) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        }
        HeartRateManager.connectTo(HeartRateDevice(address, "Strap"))
        HeartRateManager.setManaging(true)
        HeartRateManager.startDiscovery()
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        HeartRateManager.setManaging(false)
        HeartRateManager.setManaging(true)
        HeartRateManager.checkConnection()
        verify(exactly = 0) { adapter.bluetoothLeScanner }
        verify(exactly = 1) { device.connectGatt(context, false, any()) }
    }

    @Test fun `stale tap cannot replace pending or connected strap`() {
        HeartRateManager.connectTo(HeartRateDevice(address, "Strap"))
        val other = HeartRateDevice("11:12:13:14:15:16", "Other strap")
        HeartRateManager.connectTo(other)
        assertEquals(address, HeartRateManager.connectingDevice.value?.address)
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        HeartRateManager.connectTo(other)
        assertEquals(address, HeartRateManager.connectedDevice.value?.address)
        verify(exactly = 0) { adapter.getRemoteDevice(other.address); gatt.disconnect() }
        verify(exactly = 1) { device.connectGatt(context, false, any()) }
    }

    @Test fun `expired pending strap cannot regain ownership through late callback`() {
        HeartRateManager::class.java.getDeclaredField("stopped").apply {
            isAccessible = true
            (get(HeartRateManager) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        }
        HeartRateManager.connectTo(HeartRateDevice(address, "Strap"))
        field("pendingSinceMs", System.nanoTime() / 1_000_000L - 30001L)
        HeartRateManager.checkConnection()
        assertNull(HeartRateManager.connectingDevice.value)
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onCharacteristicChanged(gatt, measurement)
        assertNull(HeartRateManager.connectedDevice.value)
        assertNull(HeartRateManager.heartRate.value)
    }

}
