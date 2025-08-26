package com.spop.poverlay.ble

import android.bluetooth.*
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cycling Power Service Implementation
 * Handles the standard BLE Cycling Power Service (0x1818)
 */
class CyclingPowerServiceImpl {
    
    // GATT characteristics
    private var cyclingPowerMeasurementCharacteristic: BluetoothGattCharacteristic? = null
    private var cyclingPowerControlPointCharacteristic: BluetoothGattCharacteristic? = null
    
    // Cycling Power Feature flags
    companion object {
        // Cycling Power Feature flags (as per Bluetooth SIG spec)
        const val PEDAL_POWER_BALANCE_SUPPORTED = 0x00000001
        const val ACCUMULATED_TORQUE_SUPPORTED = 0x00000002
        const val WHEEL_REVOLUTION_DATA_SUPPORTED = 0x00000004
        const val CRANK_REVOLUTION_DATA_SUPPORTED = 0x00000008
        const val EXTREME_MAGNITUDES_SUPPORTED = 0x00000010
        const val EXTREME_ANGLES_SUPPORTED = 0x00000020
        const val TOP_AND_BOTTOM_DEAD_SPOT_ANGLES_SUPPORTED = 0x00000040
        const val ACCUMULATED_ENERGY_SUPPORTED = 0x00000080
        const val OFFSET_COMPENSATION_INDICATOR_SUPPORTED = 0x00000100
        const val OFFSET_COMPENSATION_SUPPORTED = 0x00000200
        const val MEASUREMENT_CHARACTERISTIC_CONTENT_MASKING_SUPPORTED = 0x00000400
        const val MULTIPLE_SENSOR_LOCATIONS_SUPPORTED = 0x00000800
        const val CRANK_LENGTH_ADJUSTMENT_SUPPORTED = 0x00001000
        const val CHAIN_LENGTH_ADJUSTMENT_SUPPORTED = 0x00002000
        const val CHAIN_WEIGHT_ADJUSTMENT_SUPPORTED = 0x00004000
        const val SPAN_LENGTH_ADJUSTMENT_SUPPORTED = 0x00008000
        const val SENSOR_MEASUREMENT_CONTEXT = 0x00010000
        const val INSTANTANEOUS_MEASUREMENT_DIRECTION_SUPPORTED = 0x00020000
        const val FACTORY_CALIBRATION_DATE_SUPPORTED = 0x00040000
        const val ENHANCED_OFFSET_COMPENSATION_SUPPORTED = 0x00080000
        
        // Supported features for our implementation
        const val SUPPORTED_FEATURES = (
            CRANK_REVOLUTION_DATA_SUPPORTED or
            ACCUMULATED_ENERGY_SUPPORTED
        )
        
        // Sensor Location
        const val SENSOR_LOCATION_LEFT_CRANK = 5
        const val SENSOR_LOCATION_RIGHT_CRANK = 6
        const val SENSOR_LOCATION_LEFT_PEDAL = 7
        const val SENSOR_LOCATION_RIGHT_PEDAL = 8
        const val SENSOR_LOCATION_FRONT_WHEEL = 9
        const val SENSOR_LOCATION_REAR_WHEEL = 10
        const val SENSOR_LOCATION_REAR_DROPOUT = 11
        const val SENSOR_LOCATION_CHAINSTAY = 12
        const val SENSOR_LOCATION_REAR_WHEEL_HUB = 13
        const val SENSOR_LOCATION_FRONT_HUB = 14
        const val SENSOR_LOCATION_CHAIN = 15
        
        // Our default sensor location
        const val DEFAULT_SENSOR_LOCATION = SENSOR_LOCATION_REAR_WHEEL_HUB
    }
    
    // Measurement state tracking
    private var crankRevolutions: Int = 0
    private var lastCrankEventTime: Int = 0
    private var accumulatedEnergy: Int = 0
    private var lastUpdateTimeMs: Long = 0
    
    fun createService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleUuids.CYCLING_POWER_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Cycling Power Feature characteristic (read-only)
        val cyclingPowerFeatureCharacteristic = BluetoothGattCharacteristic(
            BleUuids.CYCLING_POWER_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(cyclingPowerFeatureCharacteristic)
        
        // Cycling Power Measurement characteristic (notify)
        cyclingPowerMeasurementCharacteristic = BluetoothGattCharacteristic(
            BleUuids.CYCLING_POWER_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val measurementDescriptor = BluetoothGattDescriptor(
            BleUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        measurementDescriptor.value = byteArrayOf(0x00, 0x00)
        cyclingPowerMeasurementCharacteristic!!.addDescriptor(measurementDescriptor)
        service.addCharacteristic(cyclingPowerMeasurementCharacteristic!!)
        
        // Sensor Location characteristic (read-only)
        val sensorLocationCharacteristic = BluetoothGattCharacteristic(
            BleUuids.SENSOR_LOCATION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(sensorLocationCharacteristic)
        
        // Cycling Power Control Point characteristic (write, indicate)
        cyclingPowerControlPointCharacteristic = BluetoothGattCharacteristic(
            BleUuids.CYCLING_POWER_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val controlPointDescriptor = BluetoothGattDescriptor(
            BleUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        controlPointDescriptor.value = byteArrayOf(0x00, 0x00)
        cyclingPowerControlPointCharacteristic!!.addDescriptor(controlPointDescriptor)
        service.addCharacteristic(cyclingPowerControlPointCharacteristic!!)
        
        return service
    }
    
    fun sendPowerMeasurement(
        power: Int, 
        cadence: Int? = null, 
        notifyCallback: (BluetoothGattCharacteristic) -> Boolean
    ) {
        Timber.d("CyclingPower: Received power=${power}W, cadence=${cadence}rpm")
        cyclingPowerMeasurementCharacteristic?.let { characteristic ->
            try {
                val data = createPowerMeasurementData(power, cadence)
                characteristic.value = data
                Timber.d("Setting cycling power measurement: power=${power}W, cadence=${cadence}rpm, data=[${data.joinToString(",") { "%02x".format(it) }}]")
                
                val notificationResult = notifyCallback(characteristic)
                Timber.d("Power measurement notification result: $notificationResult")
            } catch (e: Exception) {
                Timber.e(e, "Error setting cycling power measurement")
            }
        } ?: run {
            Timber.w("Cycling power measurement characteristic is null, cannot send data")
        }
    }
    
    private fun createPowerMeasurementData(power: Int, cadence: Int?): ByteArray {
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        val currentTimeMs = System.currentTimeMillis()
        
        // Calculate time delta in seconds for energy calculation
        val deltaTimeSeconds = if (lastUpdateTimeMs > 0) {
            (currentTimeMs - lastUpdateTimeMs) / 1000f
        } else {
            0.5f // Default interval for first call
        }
        lastUpdateTimeMs = currentTimeMs
        
        // Flags field (2 bytes)
        var flags = 0
        if (cadence != null) {
            flags = flags or 0x0020 // Crank Revolution Data Present
        }
        flags = flags or 0x0080 // Accumulated Energy Present
        
        buffer.putShort(flags.toShort())
        
        // Instantaneous Power (2 bytes) - in watts
        buffer.putShort(power.toShort())
        
        // Crank Revolution Data (if present)
        if (cadence != null && cadence > 0) {
            // Simple approach: increment crank revolutions and calculate period
            val cadenceFloat = cadence.toFloat()
            val crankRevPeriod = (60 * 1024) / cadenceFloat
            
            crankRevolutions++
            lastCrankEventTime += crankRevPeriod.toInt()
            
            Timber.d("CyclingPower: cadence=${cadenceFloat}rpm, period=${crankRevPeriod.toInt()}, totalRevs=$crankRevolutions, eventTime=$lastCrankEventTime")
            
            // Cumulative Crank Revolutions (2 bytes)
            buffer.putShort((crankRevolutions and 0xFFFF).toShort())
            
            // Last Crank Event Time (2 bytes) - rolls over every 64 seconds
            buffer.putShort((lastCrankEventTime and 0xFFFF).toShort())
        }
        
        // Accumulated Energy (2 bytes) - in kilojoules
        // Calculate energy based on actual time interval: energy = power * time / 1000
        accumulatedEnergy += (power * deltaTimeSeconds / 1000).toInt()
        buffer.putShort((accumulatedEnergy and 0xFFFF).toShort())
        
        // Create the final byte array with only the used bytes
        val result = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(result)
        
        return result
    }
    
    fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic): Pair<ByteArray?, Int> {
        return when (characteristic.uuid) {
            BleUuids.CYCLING_POWER_FEATURE_UUID -> {
                // Return supported features (4 bytes)
                val data = ByteArray(4)
                data[0] = (SUPPORTED_FEATURES and 0xFF).toByte()
                data[1] = ((SUPPORTED_FEATURES shr 8) and 0xFF).toByte()
                data[2] = ((SUPPORTED_FEATURES shr 16) and 0xFF).toByte()
                data[3] = ((SUPPORTED_FEATURES shr 24) and 0xFF).toByte()
                
                Pair(data, BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.SENSOR_LOCATION_UUID -> {
                // Return sensor location (1 byte)
                val data = byteArrayOf(DEFAULT_SENSOR_LOCATION.toByte())
                Pair(data, BluetoothGatt.GATT_SUCCESS)
            }
            else -> {
                Timber.w("Unknown Cycling Power characteristic read: ${characteristic.uuid}")
                Pair(null, BluetoothGatt.GATT_READ_NOT_PERMITTED)
            }
        }
    }
    
    fun handleCharacteristicWrite(
        device: BluetoothDevice?,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        gattServer: BluetoothGattServer?
    ): Int {
        return when (characteristic.uuid) {
            BleUuids.CYCLING_POWER_CONTROL_POINT_UUID -> {
                // Handle control point commands
                if (value != null && value.isNotEmpty()) {
                    handleControlPointCommand(device, value, gattServer)
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                }
            }
            else -> {
                BluetoothGatt.GATT_WRITE_NOT_PERMITTED
            }
        }
    }
    
    private fun handleControlPointCommand(
        device: BluetoothDevice?, 
        value: ByteArray, 
        gattServer: BluetoothGattServer?
    ) {
        if (value.isEmpty()) return
        
        val opCode = value[0]
        val response = when (opCode) {
            0x01.toByte() -> {
                // Set Cumulative Value
                Timber.d("Set Cumulative Value requested by ${device?.address}")
                byteArrayOf(0x20.toByte(), opCode, 0x01.toByte()) // Success
            }
            0x02.toByte() -> {
                // Update Sensor Location
                Timber.d("Update Sensor Location requested by ${device?.address}")
                byteArrayOf(0x20.toByte(), opCode, 0x01.toByte()) // Success
            }
            0x03.toByte() -> {
                // Request Supported Sensor Locations
                Timber.d("Request Supported Sensor Locations by ${device?.address}")
                byteArrayOf(0x20.toByte(), opCode, 0x01.toByte(), DEFAULT_SENSOR_LOCATION.toByte())
            }
            0x04.toByte() -> {
                // Set Crank Length
                Timber.d("Set Crank Length requested by ${device?.address}")
                byteArrayOf(0x20.toByte(), opCode, 0x02.toByte()) // Op Code Not Supported
            }
            0x05.toByte() -> {
                // Request Crank Length
                Timber.d("Request Crank Length by ${device?.address}")
                byteArrayOf(0x20.toByte(), opCode, 0x02.toByte()) // Op Code Not Supported
            }
            else -> {
                Timber.d("Unsupported Cycling Power control op code: $opCode from ${device?.address}")
                byteArrayOf(0x20.toByte(), opCode, 0x02.toByte()) // Op Code Not Supported
            }
        }
        
        // Send indication response
        cyclingPowerControlPointCharacteristic?.let { characteristic ->
            characteristic.value = response
            try {
                device?.let {
                    gattServer?.notifyCharacteristicChanged(it, characteristic, true)
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Security exception when sending cycling power indication")
            }
        }
    }
}
