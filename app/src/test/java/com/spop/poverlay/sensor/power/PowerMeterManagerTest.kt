package com.spop.poverlay.sensor.power

import android.bluetooth.*
import android.content.Context
import android.content.SharedPreferences
import com.spop.poverlay.sensor.heartrate.HeartRateBluetoothAccess
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class PowerMeterManagerTest {
    private val context = mockk<Context>()
    private val prefs = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val access = mockk<HeartRateBluetoothAccess>(relaxed = true)
    private val device = mockk<BluetoothDevice>()
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val service = mockk<BluetoothGattService>()
    private val measurement = mockk<BluetoothGattCharacteristic>()
    private val descriptor = mockk<BluetoothGattDescriptor>()
    private val callback = slot<BluetoothGattCallback>()
    private var now = 1000L
    private lateinit var manager: PowerMeterManager
    private val info = HeartRateDevice("01:02:03:04:05:06", "Pedals")
    @Before fun setup() {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } returns null
        every { prefs.getStringSet(any(), any()) } returns emptySet()
        every { prefs.edit() } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { access.canConnect() } returns true
        every { access.remoteDevice(info.address) } returns device
        every { access.connect(device, capture(callback)) } returns gatt
        every { access.discoverServices(gatt) } returns true
        every { gatt.getService(PowerMeterManager.Service) } returns service
        every { service.getCharacteristic(PowerMeterManager.Measurement) } returns measurement
        every { measurement.getDescriptor(PowerMeterManager.Cccd) } returns descriptor
        every { measurement.uuid } returns PowerMeterManager.Measurement
        every { measurement.value } returns byteArrayOf(0, 0, 210.toByte(), 0)
        every { descriptor.uuid } returns PowerMeterManager.Cccd
        every { access.enableNotifications(gatt, measurement, descriptor) } returns true
        // The clock is advanced explicitly; no Android clock stubs or background watcher races.
        val scope = CoroutineScope(Job().apply { cancel() })
        manager = PowerMeterManager(context, access, scope, log = {}) { now }
    }
    @After fun cleanup() { manager.stop() }
    private fun connected() {
        manager.connect(info)
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        callback.captured.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        assertEquals(info, manager.connectedDevice.value)
    }
    private fun receive() = callback.captured.onCharacteristicChanged(gatt, measurement)
    @Test fun subscribesAndAcceptsBothLegacyAndModernNotifications() {
        connected(); receive()
        assertEquals(ExternalPowerReading(210, now, info.address), manager.reading.value)
        now += 100
        callback.captured.onCharacteristicChanged(gatt, measurement, byteArrayOf(0, 0, 0, 0))
        assertEquals(0, manager.reading.value?.watts)
        assertEquals(now, manager.reading.value?.timestamp)
        verify(exactly = 1) { access.discoverServices(gatt); access.enableNotifications(gatt, measurement, descriptor) }
    }
    @Test fun malformedPacketsDoNotRefreshDataOrPreventTimeout() {
        connected(); receive()
        now += 3001
        callback.captured.onCharacteristicChanged(gatt, measurement, byteArrayOf(0, 0, 42))
        manager.checkConnection()
        assertNull(manager.reading.value)
        assertEquals(info, manager.connectedDevice.value)
    }
    @Test fun p715NotificationsKeepConnectionAlivePastDataTimeout() {
        connected()
        val packet = "2f6020005ed7a261b1e1653a4a1412".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        every { measurement.value } returns packet
        repeat(30) {
            now += 1000
            receive() // Peloton Android 11 uses the legacy notification callback.
            manager.checkConnection()
            assertEquals(ExternalPowerReading(32, now, info.address), manager.reading.value)
            assertEquals(info, manager.connectedDevice.value)
        }
        verify(exactly = 0) { access.disconnect(gatt); access.close(gatt) }
    }
    @Test fun reconnectionDeadlineUsesLastPacketEvenAfterDisplayTimeoutClearsReading() {
        connected(); now += 20000; receive()
        now += 3001; manager.checkConnection()
        assertNull(manager.reading.value)
        now += 1000; manager.checkConnection()
        assertEquals(info, manager.connectedDevice.value)
        now += 8000; manager.checkConnection()
        assertNull(manager.connectedDevice.value)
        verify(exactly = 1) { access.disconnect(gatt); access.close(gatt) }
    }
    @Test fun disconnectClearsWattsAndIgnoresLateCallbacks() {
        connected(); receive(); manager.disconnect(); receive()
        callback.captured.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        assertNull(manager.reading.value)
        assertNull(manager.connectedDevice.value)
        verify { editor.remove("selected") }
    }
    @Test fun revokedPermissionClearsConnectionAndWatts() {
        connected(); receive()
        every { access.canConnect() } returns false
        manager.checkConnection()
        assertNull(manager.reading.value)
        assertNull(manager.connectedDevice.value)
        verify(exactly = 1) { access.disconnect(gatt); access.close(gatt) }
    }
    @Test fun notificationSetupFailureReleasesConnection() {
        manager.connect(info)
        every { access.enableNotifications(gatt, measurement, descriptor) } returns false
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        receive()
        assertNull(manager.connectedDevice.value)
        assertNull(manager.reading.value)
        verify(exactly = 1) { access.close(gatt) }
    }
    @Test fun failedSubscriptionDoesNotKeepMeterActive() {
        manager.connect(info)
        callback.captured.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_FAILURE)
        receive()
        assertNull(manager.reading.value)
        assertNull(manager.connectedDevice.value)
    }
    @Test fun stopReleasesResourcesAndLatePacketsCannotReactivateMeter() {
        connected(); receive(); manager.stop(); receive()
        assertNull(manager.reading.value)
        assertNull(manager.connectedDevice.value)
        verify(exactly = 1) { access.disconnect(gatt); access.close(gatt) }
    }
}
