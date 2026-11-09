package com.spop.poverlay.openbikecontrol

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import com.spop.poverlay.sensor.heartrate.HeartRateBluetoothAccess
import com.spop.poverlay.sensor.heartrate.HeartRateDevice
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ObcBluetoothClientTest {
    private var now = 0L
    private val access = mockk<HeartRateBluetoothAccess>(relaxed = true)
    private val scan = slot<ScanCallback>()
    private val callback = slot<BluetoothGattCallback>()
    private val device = mockk<BluetoothDevice>(relaxed = true)
    private val gatt = mockk<BluetoothGatt>(relaxed = true)
    private val service = mockk<BluetoothGattService>(relaxed = true)
    private val buttons = mockk<BluetoothGattCharacteristic>(relaxed = true)
    private val info = mockk<BluetoothGattCharacteristic>(relaxed = true)
    private val cccd = mockk<BluetoothGattDescriptor>(relaxed = true)
    private val links = mutableListOf<ObcLink>()
    private val appInfo = OpenBikeControl.encodeAppInfo("test")
    // No watcher: the test drives check() itself.
    private val client = ObcBluetoothClient(access, CoroutineScope(Job().apply { cancel() }), { now }, { appInfo },
        { links += it }, {}, scanRequest = { emptyList<android.bluetooth.le.ScanFilter>() to mockk() })

    @Before fun setup() {
        every { access.canConnect() } returns true
        every { access.canScan() } returns true
        every { access.startScan(any(), any(), capture(scan)) } returns true
        every { access.deviceInfo(device) } returns HeartRateDevice("AA:BB", "BikeControl")
        every { access.connect(device, capture(callback)) } returns gatt
        every { access.requestMtu(gatt, any()) } returns true
        every { access.discoverServices(gatt) } returns true
        every { access.enableNotifications(gatt, buttons, cccd) } returns true
        every { access.write(gatt, info, any()) } returns true
        every { gatt.getService(OpenBikeControl.Service) } returns service
        every { service.getCharacteristic(OpenBikeControl.ButtonState) } returns buttons
        every { service.getCharacteristic(OpenBikeControl.AppInfo) } returns info
        every { buttons.getDescriptor(OpenBikeControl.Cccd) } returns cccd
        every { cccd.uuid } returns OpenBikeControl.Cccd
        every { info.uuid } returns OpenBikeControl.AppInfo
    }

    private fun subscribe() {
        client.start()
        client.check()
        val result = mockk<ScanResult>(relaxed = true)
        every { result.device } returns device
        every { result.scanRecord } returns null
        scan.captured.onScanResult(1, result)
        callback.captured.onConnectionStateChange(gatt, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        callback.captured.onMtuChanged(gatt, 64, BluetoothGatt.GATT_SUCCESS)
        callback.captured.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)
        callback.captured.onDescriptorWrite(gatt, cccd, BluetoothGatt.GATT_SUCCESS)
    }

    @Test fun `connected only once App Info is written`() {
        subscribe()
        verify { access.write(gatt, info, appInfo) }
        assertEquals(ObcLink(ObcLink.Phase.Connecting, "BikeControl"), links.last())
        callback.captured.onCharacteristicWrite(gatt, info, BluetoothGatt.GATT_SUCCESS)
        assertEquals(ObcLink(ObcLink.Phase.Connected, "BikeControl"), links.last())
    }

    // Codex review on #101: a failed handshake must not leave a dead "Connected" link.
    @Test fun `a refused App Info write reconnects`() {
        subscribe()
        callback.captured.onCharacteristicWrite(gatt, info, 133)
        assertEquals(ObcLink(ObcLink.Phase.Searching, "BikeControl", "App Info refused (133) · reconnecting"), links.last())
        verify { access.close(gatt) }
        now += 3_000
        client.check()
        verify(exactly = 2) { access.startScan(any(), any(), any()) }
    }

    @Test fun `an App Info write that doesn't start reconnects`() {
        every { access.write(gatt, info, any()) } returns false
        subscribe()
        assertEquals(ObcLink(ObcLink.Phase.Searching, "BikeControl", "App Info write failed · reconnecting"), links.last())
        verify { access.close(gatt) }
    }

    @Test fun `a remote without App Info is connected after subscribing`() {
        every { service.getCharacteristic(OpenBikeControl.AppInfo) } returns null
        subscribe()
        assertEquals(ObcLink(ObcLink.Phase.Connected, "BikeControl"), links.last())
    }

    @Test fun `a handshake that never finishes times out`() {
        subscribe()
        now += 31_000
        client.check()
        assertEquals(ObcLink(ObcLink.Phase.Searching, "BikeControl", "Connection timed out · reconnecting"), links.last())
        verify { access.close(gatt) }
    }

    @Test fun `a connected link does not time out`() {
        subscribe()
        callback.captured.onCharacteristicWrite(gatt, info, BluetoothGatt.GATT_SUCCESS)
        now += 120_000
        client.check()
        assertEquals(ObcLink.Phase.Connected, links.last().phase)
        verify(exactly = 0) { access.close(gatt) }
    }
}
