package com.spop.poverlay.ble

import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.spop.poverlay.sensor.interfaces.SensorInterface
import io.mockk.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

@Suppress("DEPRECATION")
class BleNotificationFlowTest {
    private lateinit var ble: BleServer
    private lateinit var manager: BluetoothManager
    private lateinit var adapter: BluetoothAdapter
    private lateinit var gatt: BluetoothGattServer
    private lateinit var advertiser: BluetoothLeAdvertiser
    private lateinit var device: BluetoothDevice
    private val sent = mutableListOf<Triple<BluetoothDevice, UUID, ByteArray>>()

    @Before
    fun setup() {
        manager = mockk(relaxed = true)
        adapter = mockk(relaxed = true)
        gatt = mockk(relaxed = true)
        advertiser = mockk(relaxed = true)
        device = client("AA")
        every { manager.adapter } returns adapter
        every { adapter.state } returns BluetoothAdapter.STATE_ON
        every { adapter.bluetoothLeAdvertiser } returns advertiser
        every { manager.openGattServer(any(), any()) } returns null
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()).getString(any(), any()) } returns "1234"
        ble = BleServer(context, manager, mockk<SensorInterface>(relaxed = true), FakeTimeProvider())
        set("gattServer", gatt)
        set("isServerStarted", true)
        set("advertiser", advertiser)
        every { gatt.notifyCharacteristicChanged(any(), any(), any()) } answers {
            val characteristic = arg<BluetoothGattCharacteristic>(1)
            sent.add(Triple(arg(0), characteristic.uuid, characteristic.value.copyOf()))
            true
        }
    }

    @After
    fun cleanup() {
        ble.coroutineContext.cancel()
        unmockkAll()
    }

    private fun client(address: String): BluetoothDevice = mockk<BluetoothDevice>().also {
        every { it.address } returns address
    }

    private fun characteristic(uuid: UUID = FitnessMachineConstants.IndoorBikeDataUUID): BluetoothGattCharacteristic {
        var value = byteArrayOf(1)
        return mockk<BluetoothGattCharacteristic>(relaxed = true).also { ch ->
            every { ch.uuid } returns uuid
            every { ch.value } answers { value }
            every { ch.setValue(any<ByteArray>()) } answers { value = firstArg(); true }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun subscribe(client: BluetoothDevice, ch: BluetoothGattCharacteristic) {
        (get("notificationSubscriptions") as MutableMap<String, MutableSet<UUID>>)
            .getOrPut(client.address) { mutableSetOf() }.add(ch.uuid)
    }

    private fun get(name: String): Any? = BleServer::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(ble)

    private fun set(name: String, value: Any?) = BleServer::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.set(ble, value)

    private fun invoke(name: String, vararg args: Any): Any? = BleServer::class.java.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(ble, *args)

    @Test
    fun `only completion sends next packet across clients and replaces stale telemetry`() {
        val ch = characteristic()
        val second = client("BB")
        subscribe(device, ch)
        subscribe(second, ch)
        ble.notifyCharacteristicChanged(device, ch, false)
        ch.value = byteArrayOf(2)
        ble.notifyCharacteristicChanged(second, ch, false)
        ch.value = byteArrayOf(3)
        ble.notifyCharacteristicChanged(second, ch, false)
        ch.value[0] = 9 // A later caller mutation must not change the queued snapshot.
        assertEquals(1, sent.size)
        ble.onNotificationSent(second, BluetoothGatt.GATT_SUCCESS)
        assertEquals(1, sent.size)
        ble.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        assertEquals(2, sent.size)
        assertEquals(second, sent[1].first)
        assertArrayEquals(byteArrayOf(3), sent[1].third)
        assertArrayEquals(byteArrayOf(9), ch.value)
    }

    @Test
    fun `control indications and status events retain order and values`() {
        val telemetry = characteristic()
        val control = characteristic(FitnessMachineConstants.ControlPointUUID)
        val status = characteristic(FitnessMachineConstants.MachineStatusUUID)
        listOf(telemetry, control, status).forEach { subscribe(device, it) }
        ble.notifyCharacteristicChanged(device, telemetry, false)
        for (value in 1..3) {
            control.value = byteArrayOf(value.toByte())
            ble.notifyCharacteristicChanged(device, control, true)
            status.value = byteArrayOf((value + 10).toByte())
            ble.notifyCharacteristicChanged(device, status, false)
        }
        repeat(7) { ble.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS) }
        assertEquals(listOf(1, 1, 11, 2, 12, 3, 13), sent.map { it.third[0].toInt() })
        verify(exactly = 3) { gatt.notifyCharacteristicChanged(device, control, true) }
    }

    @Test
    fun `queue remains bounded and overflow evicts telemetry before control responses`() {
        val telemetry = characteristic()
        val control = characteristic(FitnessMachineConstants.ControlPointUUID)
        subscribe(device, telemetry)
        subscribe(device, control)
        ble.notifyCharacteristicChanged(device, telemetry, false)
        ble.notifyCharacteristicChanged(device, telemetry, false)
        repeat(64) { ble.notifyCharacteristicChanged(device, control, true) }
        assertEquals(64, (get("pendingNotifications") as List<*>).size)
        repeat(65) { ble.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS) }
        assertEquals(65, sent.size)
        assertEquals(64, sent.count { it.second == control.uuid })
    }

    @Test
    fun `control-only overflow disconnects instead of silently losing responses`() {
        val control = characteristic(FitnessMachineConstants.ControlPointUUID)
        subscribe(device, control)
        repeat(66) { ble.notifyCharacteristicChanged(device, control, true) }
        verify(exactly = 1) { gatt.cancelConnection(device) }
        assertTrue((get("pendingNotifications") as List<*>).isEmpty())
        ble.onNotificationSent(device, BluetoothGatt.GATT_SUCCESS)
        assertEquals(1, sent.size)
    }

    @Test
    fun `send rejection and exception disconnect without waiting for nonexistent completion`() {
        val ch = characteristic()
        subscribe(device, ch)
        every { gatt.notifyCharacteristicChanged(device, ch, false) } returns false
        ble.notifyCharacteristicChanged(device, ch, false)
        assertNull(get("notificationInFlight"))
        verify(exactly = 1) { gatt.cancelConnection(device) }
        subscribe(device, ch)
        every { gatt.notifyCharacteristicChanged(device, ch, false) } throws IllegalStateException("Bluetooth off")
        ble.notifyCharacteristicChanged(device, ch, false)
        assertNull(get("notificationInFlight"))
        verify(exactly = 2) { gatt.cancelConnection(device) }
    }

    @Test
    fun `failed completion drops failed client queue but allows other clients to progress`() {
        val ch = characteristic()
        val second = client("BB")
        subscribe(device, ch)
        subscribe(second, ch)
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.notifyCharacteristicChanged(second, ch, false)
        ble.onNotificationSent(device, 129)
        verify { gatt.cancelConnection(device) }
        assertEquals(listOf(device, second), sent.map { it.first })
    }

    @Test
    fun `disconnect drops queued packets while outstanding callback still gates next send`() {
        val ch = characteristic()
        val second = client("BB")
        subscribe(device, ch)
        subscribe(second, ch)
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.notifyCharacteristicChanged(second, ch, false)
        ble.onConnectionStateChange(device, 0, BluetoothProfile.STATE_DISCONNECTED)
        assertEquals(1, sent.size)
        ble.onNotificationSent(device, 0)
        assertEquals(listOf(device, second), sent.map { it.first })
    }

    @Test
    fun `unsubscribe removes queued packets`() {
        val ch = characteristic()
        subscribe(device, ch)
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.notifyCharacteristicChanged(device, ch, false)
        val descriptor = mockk<BluetoothGattDescriptor>(relaxed = true)
        every { descriptor.uuid } returns FitnessMachineConstants.ClientCharacteristicConfigurationUUID
        every { descriptor.characteristic } returns ch
        ble.onDescriptorWriteRequest(device, 1, descriptor, false, false, 0,
            byteArrayOf(0, 0))
        ble.onNotificationSent(device, 0)
        assertEquals(1, sent.size)
        assertTrue((get("pendingNotifications") as List<*>).isEmpty())
    }

    @Test
    fun `timeout retires registration and ignores stale completion and stale timeout`() {
        val ch = characteristic()
        subscribe(device, ch)
        val oldCallback = invoke("callbackForGeneration", 0L) as BluetoothGattServerCallback
        ble.notifyCharacteristicChanged(device, ch, false)
        val timedOut = get("notificationInFlight")!!
        invoke("handleNotificationTimeout", 0L, timedOut)
        verify(exactly = 1) { gatt.close() }
        assertNull(get("notificationInFlight"))
        assertTrue((get("notificationSubscriptions") as Map<*, *>).isEmpty())
        set("gattServer", gatt)
        subscribe(device, ch)
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.notifyCharacteristicChanged(device, ch, false)
        oldCallback.onNotificationSent(device, 0)
        invoke("handleNotificationTimeout", 0L, timedOut)
        assertEquals(2, sent.size)
        verify(exactly = 1) { gatt.close() }
        val current = invoke("callbackForGeneration", get("gattServerGeneration")!!) as BluetoothGattServerCallback
        current.onNotificationSent(device, 0)
        assertEquals(3, sent.size)
    }

    @Test
    fun `stop clears pending work and rejects callbacks from stopped registration`() {
        val ch = characteristic()
        subscribe(device, ch)
        val callback = invoke("callbackForGeneration", 0L) as BluetoothGattServerCallback
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.notifyCharacteristicChanged(device, ch, false)
        ble.stop()
        callback.onNotificationSent(device, 0)
        assertEquals(1, sent.size)
        assertNull(get("notificationInFlight"))
        assertTrue((get("pendingNotifications") as List<*>).isEmpty())
    }

    @Test
    fun `adapter turning off retires BLE work and does not call advertiser`() {
        val ch = characteristic()
        subscribe(device, ch)
        ble.notifyCharacteristicChanged(device, ch, false)
        invoke("handleBluetoothStateChange", BluetoothAdapter.STATE_TURNING_OFF)
        invoke("startAdvertising")
        ble.notifyCharacteristicChanged(device, ch, false)
        verify(exactly = 0) { advertiser.startAdvertising(any(), any(), any(), any()) }
        verify(exactly = 1) { gatt.close() }
        assertEquals(1, sent.size)
        assertNull(get("notificationInFlight"))
    }

    @Test
    fun `adapter state blocks advertising before the shutdown broadcast arrives`() {
        prepareAdvertising()
        every { adapter.state } returns BluetoothAdapter.STATE_TURNING_OFF
        invoke("startAdvertising")
        verify(exactly = 0) { advertiser.startAdvertising(any(), any(), any(), any()) }
        every { adapter.state } throws SecurityException("Permission revoked")
        invoke("startAdvertising")
        verify(exactly = 0) { advertiser.startAdvertising(any(), any(), any(), any()) }
    }

    @Test
    fun `Bluetooth on requests a fresh registration and clears suspension`() {
        invoke("handleBluetoothStateChange", BluetoothAdapter.STATE_TURNING_OFF)
        invoke("handleBluetoothStateChange", BluetoothAdapter.STATE_OFF)
        invoke("handleBluetoothStateChange", BluetoothAdapter.STATE_ON)
        verify(exactly = 1) { manager.openGattServer(any(), any()) }
        assertEquals(false, get("bluetoothSuspended"))
        assertEquals(true, get("isServerStarted"))
    }

    @Test
    fun `stop cancels scheduled advertising restart`() = runBlocking {
        invoke("scheduleAdvertisingRestart")
        val restart = get("advertisingRestartJob") as Job
        ble.stop()
        restart.join()
        assertTrue(restart.isCancelled)
        verify(exactly = 0) { advertiser.startAdvertising(any(), any(), any(), any()) }
    }

    private fun prepareAdvertising() {
        mockkConstructor(AdvertiseSettings.Builder::class, AdvertiseData.Builder::class, ParcelUuid::class)
        every { anyConstructed<ParcelUuid>().uuid } returns FitnessMachineConstants.ServiceUUID
        every { anyConstructed<AdvertiseSettings.Builder>().setAdvertiseMode(any()) } answers { self as AdvertiseSettings.Builder }
        every { anyConstructed<AdvertiseSettings.Builder>().setTxPowerLevel(any()) } answers { self as AdvertiseSettings.Builder }
        every { anyConstructed<AdvertiseSettings.Builder>().setConnectable(any()) } answers { self as AdvertiseSettings.Builder }
        every { anyConstructed<AdvertiseSettings.Builder>().build() } returns mockk()
        every { anyConstructed<AdvertiseData.Builder>().addServiceUuid(any()) } answers { self as AdvertiseData.Builder }
        every { anyConstructed<AdvertiseData.Builder>().setIncludeDeviceName(any()) } answers { self as AdvertiseData.Builder }
        every { anyConstructed<AdvertiseData.Builder>().addManufacturerData(any(), any()) } answers { self as AdvertiseData.Builder }
        every { anyConstructed<AdvertiseData.Builder>().build() } returns mockk()
        val service = mockk<BaseBleService>(relaxed = true)
        every { service.service.uuid } returns FitnessMachineConstants.ServiceUUID
        @Suppress("UNCHECKED_CAST")
        (get("registeredServices") as MutableList<BaseBleService>).add(service)
    }

    @Test
    fun `adapter shutdown between state check and advertising start is contained`() {
        prepareAdvertising()
        every { advertiser.startAdvertising(any(), any(), any(), any()) } throws IllegalStateException("BT Adapter is not turned ON")
        invoke("startAdvertising")
        verify(exactly = 1) { advertiser.startAdvertising(any(), any(), any(), any()) }
        assertNull(get("advertisingCallback"))
        assertEquals(false, get("isAdvertising"))
    }

    @Test
    fun `advertising attempt is single flight and stale success cannot revive stopped advertising`() {
        prepareAdvertising()
        invoke("startAdvertising")
        val callback = get("advertisingCallback") as AdvertiseCallback
        invoke("startAdvertising")
        verify(exactly = 1) { advertiser.startAdvertising(any(), any(), any(), any()) }
        every { advertiser.stopAdvertising(any()) } throws IllegalStateException("Bluetooth off")
        invoke("stopAdvertising")
        callback.onStartSuccess(null)
        callback.onStartFailure(AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED)
        assertEquals(false, get("isAdvertising"))
        assertNull(get("advertisingCallback"))
    }

    @Test
    @android.annotation.TargetApi(33)
    fun `API 33 submission checks return status and passes immutable payload explicitly`() {
        val ch = characteristic()
        val payload = byteArrayOf(8)
        every { gatt.notifyCharacteristicChanged(device, ch, true, payload) } returns BluetoothStatusCodes.SUCCESS
        assertTrue(sendGattNotificationWithValue(gatt, device, ch, true, payload))
        every { gatt.notifyCharacteristicChanged(device, ch, true, payload) } returns BluetoothStatusCodes.ERROR_UNKNOWN
        assertFalse(sendGattNotificationWithValue(gatt, device, ch, true, payload))
        every { gatt.notifyCharacteristicChanged(device, ch, true, payload) } throws SecurityException("Permission revoked")
        assertFalse(sendGattNotificationWithValue(gatt, device, ch, true, payload))
        assertArrayEquals(byteArrayOf(1), ch.value)
        verify(exactly = 0) { gatt.notifyCharacteristicChanged(any(), any(), any()) }
    }
}
