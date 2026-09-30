package com.spop.poverlay.sensor.heartrate

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class HeartRateBluetoothAccessTest {
    private val context = mockk<Context>()
    private val adapter = mockk<BluetoothAdapter>()
    private val scanner = mockk<BluetoothLeScanner>(relaxed = true)
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val device = mockk<BluetoothDevice>()
    private val callback = mockk<BluetoothGattCallback>()
    private val scanCallback = mockk<ScanCallback>()
    private val settings = mockk<ScanSettings>()
    private val characteristic = mockk<BluetoothGattCharacteristic>()
    private val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
    private val grantedPermissions = mutableSetOf<String>()
    private lateinit var access: HeartRateBluetoothAccess

    @Before fun setup() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(context, any()) } answers {
            if (secondArg<String>() in grantedPermissions) PackageManager.PERMISSION_GRANTED
            else PackageManager.PERMISSION_DENIED
        }
        grantedPermissions.addAll(listOf(
            Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))
        every { adapter.bluetoothLeScanner } returns scanner
        every { device.address } returns "01:02:03:04:05:06"
        every { device.name } returns "HR strap"
        every { device.connectGatt(context, false, callback) } returns gatt
        every { gatt.discoverServices() } returns true
        every { gatt.setCharacteristicNotification(characteristic, true) } returns true
        every { gatt.writeDescriptor(descriptor) } returns true
        access = HeartRateBluetoothAccess(context, 34) { adapter }
    }

    @After fun cleanup() { unmockkStatic(ContextCompat::class) }

    @Test fun `legacy Android uses legacy Bluetooth and runtime location grants`() {
        grantedPermissions.remove(Manifest.permission.BLUETOOTH_CONNECT)
        grantedPermissions.remove(Manifest.permission.BLUETOOTH_SCAN)
        for (sdk in listOf(21, 23, 29, 30)) {
            val legacy = HeartRateBluetoothAccess(context, sdk) { adapter }
            assertTrue("connect on $sdk", legacy.canConnect())
            assertTrue("scan on $sdk", legacy.canScan())
        }
        grantedPermissions.remove(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(HeartRateBluetoothAccess(context, 21).canScan())
        assertFalse(HeartRateBluetoothAccess(context, 23).canScan())
        assertFalse(HeartRateBluetoothAccess(context, 30).canScan())
    }

    @Test fun `Android 12 and newer require nearby grants and current manifest location grant`() {
        for (sdk in listOf(31, 34)) {
            val modern = HeartRateBluetoothAccess(context, sdk)
            for (permission in listOf(Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.ACCESS_FINE_LOCATION)) {
                grantedPermissions.remove(permission)
                assertFalse("$permission on $sdk", modern.canScan())
                grantedPermissions.add(permission)
            }
            assertTrue(modern.canScan())
        }
    }

    @Test fun `denied grants prevent scan and all GATT operations`() {
        grantedPermissions.clear()
        assertFalse(access.startScan(emptyList(), settings, scanCallback))
        assertNull(access.remoteDevice("01:02:03:04:05:06"))
        assertNull(access.deviceInfo(device))
        assertNull(access.connect(device, callback))
        assertFalse(access.discoverServices(gatt))
        assertFalse(access.enableNotifications(gatt, characteristic, descriptor))
        verify { adapter wasNot Called; device wasNot Called; gatt wasNot Called }
    }

    @Test fun `granted operations reach Android and preserve successful results`() {
        assertTrue(access.startScan(emptyList(), settings, scanCallback))
        assertEquals(HeartRateDevice("01:02:03:04:05:06", "HR strap"), access.deviceInfo(device))
        assertSame(gatt, access.connect(device, callback))
        assertTrue(access.discoverServices(gatt))
        assertTrue(access.enableNotifications(gatt, characteristic, descriptor))
        verify(exactly = 1) { scanner.startScan(emptyList(), settings, scanCallback) }
        verify(exactly = 1) { gatt.writeDescriptor(descriptor) }
    }

    @Test fun `revocation after grant check cannot crash scan or connection`() {
        every { scanner.startScan(any<List<ScanFilter>>(), settings, scanCallback) } throws SecurityException()
        every { device.name } throws SecurityException()
        every { device.connectGatt(context, false, callback) } throws SecurityException()
        every { gatt.discoverServices() } throws SecurityException()
        assertFalse(access.startScan(emptyList(), settings, scanCallback))
        assertNull(access.deviceInfo(device))
        assertNull(access.connect(device, callback))
        assertFalse(access.discoverServices(gatt))
    }

    @Test fun `revocation in either notification step reports failure`() {
        every { gatt.setCharacteristicNotification(characteristic, true) } throws SecurityException()
        assertFalse(access.enableNotifications(gatt, characteristic, descriptor))
        verify(exactly = 0) { gatt.writeDescriptor(descriptor) }
        every { gatt.setCharacteristicNotification(characteristic, true) } returns true
        every { gatt.writeDescriptor(descriptor) } throws SecurityException()
        assertFalse(access.enableNotifications(gatt, characteristic, descriptor))
    }

    @Test fun `cleanup tolerates permission revocation for every operation`() {
        grantedPermissions.clear()
        every { scanner.stopScan(scanCallback) } throws SecurityException()
        every { gatt.disconnect() } throws SecurityException()
        every { gatt.close() } throws SecurityException()
        access.stopScan(scanCallback)
        access.disconnect(gatt)
        access.close(gatt)
        verify(exactly = 1) { scanner.stopScan(scanCallback); gatt.disconnect(); gatt.close() }
    }

    @Test fun `disabled radio and scanner lookup denial report no active scan`() {
        every { adapter.bluetoothLeScanner } returns null
        assertFalse(access.startScan(emptyList(), settings, scanCallback))
        every { adapter.bluetoothLeScanner } throws SecurityException()
        assertFalse(access.startScan(emptyList(), settings, scanCallback))
        access.stopScan(scanCallback)
        every { adapter.bluetoothLeScanner } returns scanner
        every { scanner.startScan(any<List<ScanFilter>>(), settings, scanCallback) } throws IllegalStateException()
        assertFalse(access.startScan(emptyList(), settings, scanCallback))
    }

    @Test fun `newly revoked permission is checked again on later operations`() {
        assertSame(gatt, access.connect(device, callback))
        grantedPermissions.remove(Manifest.permission.BLUETOOTH_CONNECT)
        assertNull(access.connect(device, callback))
        assertFalse(access.discoverServices(gatt))
        verify(exactly = 1) { device.connectGatt(context, false, callback) }
        verify(exactly = 0) { gatt.discoverServices() }
    }
}
