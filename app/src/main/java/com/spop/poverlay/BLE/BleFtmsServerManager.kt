package com.spop.poverlay.BLE





import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

class BleFtmsServerManager(private val context: Context ) {

    public class ControlPointData ()
    {
        public var grade:Double = 0.0
        public var rollingResistance:Double = 0.0
        public var windSpeed:Double = 0.0
        public var cw:Double =0.0
    }
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var bluetoothGattServer: BluetoothGattServer? = null
    private val registeredDevices = mutableSetOf<BluetoothDevice>()

    private var lastPower = 0
    private var lastCadence = 0f
    private var lastSpeed = 0f // in km/h

    // FTMS UUIDs
    private val FTMS_SERVICE_UUID = UUID.fromString("00001826-0000-1000-8000-00805F9B34FB")
    private val INDOOR_BIKE_DATA_UUID = UUID.fromString("00002AD2-0000-1000-8000-00805F9B34FB")
    private val FTMS_FEATURE_UUID = UUID.fromString("00002ACC-0000-1000-8000-00805F9B34FB")
    private val FTMS_CONTROL_POINT_UUID = UUID.fromString("00002AD9-0000-1000-8000-00805F9B34FB")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Callbacks
    var onConnectionStateChanged: ((Int) -> Unit)? = null
    var onResistanceChanged: ((Int) -> Unit)? = null

    var onControlPointChanged: ((ControlPointData) -> Unit)? = null

    init {
        bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    }

    suspend fun observePower(power: Flow<Float>) {
        power.collect { value ->
            lastPower = value.toInt()
            updateBikeData()
        }
    }

    suspend fun observeCadence(cadence: Flow<Float>) {
        cadence.collect { value ->
            lastCadence = value.toFloat()
            updateBikeData()
        }
    }

    suspend fun observeSpeed(speed: Flow<Float>) {
        speed.collect { value ->
            lastSpeed = value.toFloat()
            updateBikeData()
        }
    }

    private var lastHeartRate = 0

    suspend fun observeHeartRate(heartRate: Flow<String>) {
        heartRate.collect { value ->
            try {
                lastHeartRate = value.toInt()
                updateBikeData()
            }
            catch (e: Exception)
            {}
        }
    }

    @SuppressLint("MissingPermission")
    public fun updateBikeData() {
        if (registeredDevices.isEmpty()) return

        val characteristic = bluetoothGattServer?.getService(FTMS_SERVICE_UUID)
            ?.getCharacteristic(INDOOR_BIKE_DATA_UUID) ?: return

        // FTMS Indoor Bike Data Format
        // Flags: 16-bit
        // Bit 0: More Data (0 = False)
        // Bit 1: Average Speed present (0 = False)
        // Bit 2: Instantaneous Cadence present (1 = True)
        // Bit 3: Average Cadence present (0 = False)
        // Bit 4: Total Distance present (0 = False)
        // Bit 5: Resistance Level present (0 = False for now, as we only report sensors)
        // Bit 6: Instantaneous Power present (1 = True)
        // Bit 7: Average Power present (0 = False)
        // Bit 8: Expended Energy present (0 = False)
        // ...

        // Let's use Flags: 0x0044 (Bits 2 and 6)
        // Wait, Bit 0 is Speed. If Bit 0 is NOT set, Speed is present?
        // Actually, Bit 0 is "More Data". Bit 1 is "Average Speed".
        // Bit 0 = 0 means Instantaneous Speed IS present.

        var flags = 0x0000
        // Bit 0: Instantaneous Speed present (0 means it IS present according to spec if bit is 0? No, bit 0 is "More Data")
        // The spec says:
        // Flags (16 bits)
        // Bit 0: More Data (0: False, 1: True)
        // Bit 1: Average Speed Present
        // Bit 2: Instantaneous Cadence Present
        // Bit 3: Average Cadence Present
        // ...
        // If Bit 0 is 0, Instantaneous Speed is ALWAYS present.

        flags = flags or (1 shl 2) // Instantaneous Cadence
        flags = flags or (1 shl 6) // Instantaneous Power
        flags = flags or (1 shl 10) // Heart Rate present (Bit 10)


        val data = mutableListOf<Byte>()
        // Flags (uint16)
        data.add((flags and 0xFF).toByte())
        data.add(((flags shr 8) and 0xFF).toByte())

        // Instantaneous Speed (uint16, unit 0.01km/h)
        val speedVal = (lastSpeed * 100).toInt()
        data.add((speedVal and 0xFF).toByte())
        data.add(((speedVal shr 8) and 0xFF).toByte())

        // Instantaneous Cadence (uint16, unit 0.5rpm)
        val cadenceVal = (lastCadence * 2).toInt()
        data.add((cadenceVal and 0xFF).toByte())
        data.add(((cadenceVal shr 8) and 0xFF).toByte())

        // Instantaneous Power (sint16, unit 1W)
        data.add((lastPower and 0xFF).toByte())
        data.add(((lastPower shr 8) and 0xFF).toByte())

        characteristic.value = data.toByteArray()

        for (device in registeredDevices) {
            bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) return

        setupGattServer()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)

            .addServiceUuid(android.os.ParcelUuid(FTMS_SERVICE_UUID))
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
        val service = BluetoothGattService(FTMS_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        // Indoor Bike Data (Notify)
        val bikeData = BluetoothGattCharacteristic(
            INDOOR_BIKE_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        bikeData.addDescriptor(BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))

        // FTMS Feature (Read)
        val feature = BluetoothGattCharacteristic(
            FTMS_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // Features: bit 0: Speed, bit 1: Cadence, bit 4: Power, bit 5: Resistance Level
        // 0x00000033 (bits 0, 1, 4, 5)
        feature.value = byteArrayOf(0x33, 0x00, 0x00, 0x00)

        // Control Point (Write, Indicate)
        val controlPoint = BluetoothGattCharacteristic(
            FTMS_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        controlPoint.addDescriptor(BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ))

        service.addCharacteristic(bikeData)
        service.addCharacteristic(feature)
        service.addCharacteristic(controlPoint)

        bluetoothGattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
        bluetoothGattServer?.addService(service)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i("BleFtmsServerManager", "FTMS Advertising started")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("BleFtmsServerManager", "FTMS Advertising failed: $errorCode")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                registeredDevices.add(device)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                registeredDevices.remove(device)
            }
            onConnectionStateChanged?.invoke(registeredDevices.size)
        }
        var msg:String = "none"
        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // var msg:String = "none"
            if (characteristic.uuid == FTMS_CONTROL_POINT_UUID) {
                if (value.isNotEmpty()) {
                    val opCode = value[0]
                    when (opCode) {
                        0x00.toByte() -> { // Request Control
                            sendControlPointResponse(device, requestId, opCode, 0x01)
                        }
                        0x04.toByte() -> { // Set Target Resistance Level
                            if (value.size >= 2) {
                                val resistance = value[1].toInt()
                                onResistanceChanged?.invoke(resistance)
                                sendControlPointResponse(device, requestId, opCode, 0x01)
                            } else {
                                sendControlPointResponse(device, requestId, opCode, 0x03) // Invalid Parameter
                            }
                        }
                        0x11.toByte() -> {
                            if (value.size >= 7) {



                                val grade = ByteBuffer.wrap(value, 3, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.01

                                //val grade = ByteBuffer.wrap(value, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.01
                                val crr = (value[5].toInt() and 0xFF) * 0.0001
                                val windSpeed = ByteBuffer.wrap(value, 1, 2).order(ByteOrder.LITTLE_ENDIAN).short * 0.001
                                val cw = (value[6].toInt() and 0xFF) * 0.01
                                var cp  = ControlPointData()
                                cp.grade = grade.toDouble()
                                cp.rollingResistance = crr.toDouble()
                                cp.windSpeed = windSpeed.toDouble()
                                cp.cw = cw.toDouble()

                                onControlPointChanged?.invoke(cp)





                                sendControlPointResponse(device, requestId, opCode, 0x01)
                            } else "Simulation parameters"
                        }
                        else -> {
                            sendControlPointResponse(device, requestId, opCode, 0x01) // Not Supported
                        }
                    }
                }
            }
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

        }

        @SuppressLint("MissingPermission")
        private fun sendControlPointResponse(device: BluetoothDevice, requestId: Int, opCode: Byte, result: Byte) {
            val characteristic = bluetoothGattServer?.getService(FTMS_SERVICE_UUID)
                ?.getCharacteristic(FTMS_CONTROL_POINT_UUID) ?: return

            // Response format: 0x80, Request Op Code, Result Code
            characteristic.value = byteArrayOf(0x80.toByte(), opCode, result)
            bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, true)
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
                if (Arrays.equals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, value) ||
                    Arrays.equals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, value)) {
                    registeredDevices.add(device)
                }
            }
            if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, characteristic.value)
        }
    }
}
