package com.spop.poverlay.BLE

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import android.os.Handler
import android.os.Looper
import java.util.Timer
import java.util.TimerTask

import kotlin.time.measureTime

class BleServerManager(private val context: Context) {

    private var timer: Timer? = null
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    var lastInstant: Instant = Clock.System.now()
    var currentInstant: Instant = Clock.System.now()

    var sensorStartInstant: Instant = Clock.System.now()

    var lastTDCInstant: Instant = Clock.System.now()

    var totalRevsDouble: Double = 0.0
    var lastPower = 0

    var previousTime = 0


    var LastCrankEventTimeInt = 0

    // UUIDs for Cycling Power Service
    private val SERVICE_UUID = UUID.fromString("00001818-0000-1000-8000-00805F9B34FB")
    private val CHARACTERISTIC_POWER_MEASUREMENT_UUID =
        UUID.fromString("00002A63-0000-1000-8000-00805F9B34FB")
    private val CHARACTERISTIC_POWER_FEATURE_UUID =
        UUID.fromString("00002A65-0000-1000-8000-00805F9B34FB")
    private val CHARACTERISTIC_SENSOR_LOCATION_UUID =
        UUID.fromString("00002A5D-0000-1000-8000-00805F9B34FB")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Simulation state
    private var cumulativeCrankRevolutions = 0
    private var lastCrankEventTime = 0
    private var crankRevsFloat = 0.0
    private var simulationTime = 0

    // Callback to update UI about connection status
    var onConnectionStateChanged: ((Int) -> Unit)? = null

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    }

    suspend fun observePower(power: Flow<String>) {
        power.collect { value ->
            val powerInt = value.toIntOrNull() ?: 0
            lastPower = powerInt

            postPower()
        }
    }

    @SuppressLint("MissingPermission")
    private fun postPower() {
        if (!registeredDevices.isEmpty()) return


        val characteristic = bluetoothGattServer?.getService(SERVICE_UUID)
            ?.getCharacteristic(CHARACTERISTIC_POWER_MEASUREMENT_UUID) ?: return

        val fullValue = ByteArray(8)
        val flags = 0x20 // Crank Data Present

        // Flags (16 bit)
        fullValue[0] = (flags and 0xFF).toByte()
        fullValue[1] = ((flags shr 8) and 0xFF).toByte()

        // Power (16 bit, sint16)
        fullValue[2] = (lastPower and 0xFF).toByte()
        fullValue[3] = ((lastPower shr 8) and 0xFF).toByte()




        characteristic.value = fullValue

        for (device in registeredDevices) {
            bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }


    }

    suspend fun observeCadence(cadence: Flow<String>) {
        cadence.collect { value ->
            val cadenceInt = value.toIntOrNull() ?: 0




            postCadence(cadenceInt)

        }
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalTime::class)
    private fun postCadence(cadenceInt: Int) {
        if (registeredDevices.isEmpty()) return

        currentInstant = Clock.System.now()
        val duration: Duration = currentInstant - lastInstant
        lastInstant = currentInstant


        totalRevsDouble += (duration.inWholeMilliseconds.toDouble() * cadenceInt.toDouble() / 60000.0)



        if (floor(totalRevsDouble) > cumulativeCrankRevolutions) {
            previousTime = LastCrankEventTimeInt

            /// calculate revs past tdc
            val revsPastTDC = totalRevsDouble - floor(totalRevsDouble)
            /// rev per milisecond = cadence / 60000
            ///calculate time at TDC

            var msPastTDC: Int = 0
            if (cadenceInt > 0)
                msPastTDC = (revsPastTDC * (1 / cadenceInt.toDouble()) * 60000.00).toInt()

            if (msPastTDC > 0)

                lastTDCInstant = currentInstant - Duration.milliseconds(
                    msPastTDC
                )


            /// calculate the difference between the starting time and the last TDC
            val durationTDC: Duration = lastTDCInstant - sensorStartInstant

            LastCrankEventTimeInt =
                floor(1024.00 * durationTDC.inWholeMilliseconds.toDouble() / (1000.0)).toInt() % 65536
            cumulativeCrankRevolutions = floor(totalRevsDouble).toInt() % 65536


        }


        val characteristic = bluetoothGattServer?.getService(SERVICE_UUID)
            ?.getCharacteristic(CHARACTERISTIC_POWER_MEASUREMENT_UUID) ?: return

        val fullValue = ByteArray(8)
        val flags = 0x20 // Crank Data Present

        // Flags (16 bit)
        fullValue[0] = (flags and 0xFF).toByte()
        fullValue[1] = ((flags shr 8) and 0xFF).toByte()

        // Power (16 bit, sint16)
        fullValue[2] = (lastPower and 0xFF).toByte()
        fullValue[3] = ((lastPower shr 8) and 0xFF).toByte()


        // Cumulative Crank Revolutions (16 bit, uint16)
        fullValue[4] = (cumulativeCrankRevolutions and 0xFF).toByte()
        fullValue[5] = ((cumulativeCrankRevolutions shr 8) and 0xFF).toByte()

        // Last Crank Event Time (16 bit, uint16)
        fullValue[6] = (LastCrankEventTimeInt and 0xFF).toByte()
        fullValue[7] = ((LastCrankEventTimeInt shr 8) and 0xFF).toByte()

        characteristic.value = fullValue

        for (device in registeredDevices) {
            bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e("BleServerManager", "Bluetooth not enabled")
            return
        }

        setupGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)

            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)

    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        bluetoothGattServer?.close()

    }




    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Power Measurement Characteristic (Notify)
        val powerMeasurement = BluetoothGattCharacteristic(
            CHARACTERISTIC_POWER_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val configDescriptor = BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        powerMeasurement.addDescriptor(configDescriptor)

        // Power Feature Characteristic (Read)
        val powerFeature = BluetoothGattCharacteristic(
            CHARACTERISTIC_POWER_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Feature: 0x00000008 (Crank Revolution Data Supported)
        powerFeature.value = byteArrayOf(0x08, 0x00, 0x00, 0x00)

        // Sensor Location Characteristic (Read)
        val sensorLocation = BluetoothGattCharacteristic(
            CHARACTERISTIC_SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Location: 13 (Rear Hub)
        sensorLocation.value = byteArrayOf(13)

        service.addCharacteristic(powerMeasurement)
        service.addCharacteristic(powerFeature)
        service.addCharacteristic(sensorLocation)

        bluetoothGattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        bluetoothGattServer?.addService(service)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("BleServerManager", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("BleServerManager", "Advertising failed: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                registeredDevices.add(device)
                Log.i("BleServerManager", "Device connected: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                registeredDevices.remove(device)
                Log.i("BleServerManager", "Device disconnected: ${device.address}")
            }
            onConnectionStateChanged?.invoke(registeredDevices.size)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (CLIENT_CHARACTERISTIC_CONFIG_UUID == descriptor.uuid) {
                if (java.util.Arrays.equals(
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE,
                        value
                    )
                ) {
                    Log.i("BleServerManager", "Notifications enabled for ${device.address}")
                    registeredDevices.add(device)
                } else if (java.util.Arrays.equals(
                        BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE,
                        value
                    )
                ) {
                    Log.i("BleServerManager", "Notifications disabled for ${device.address}")
                }
            }
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_SUCCESS,
                    0,
                    null
                )
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            bluetoothGattServer?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                0,
                characteristic.value
            )
        }
    }


}
