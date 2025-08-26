package com.spop.poverlay.ble

import android.bluetooth.*
import timber.log.Timber

/**
 * FTMS (Fitness Machine Service) Implementation
 * Handles all FTMS-related GATT characteristics and operations
 */
class FtmsServiceImpl {
    
    // GATT characteristics
    private var indoorBikeDataCharacteristic: BluetoothGattCharacteristic? = null
    private var fitnessMachineStatusCharacteristic: BluetoothGattCharacteristic? = null
    private var fitnessMachineControlPointCharacteristic: BluetoothGattCharacteristic? = null
    
    fun createService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleUuids.FITNESS_MACHINE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Fitness Machine Feature characteristic (read-only)
        val fitnessMachineFeatureCharacteristic = BluetoothGattCharacteristic(
            BleUuids.FITNESS_MACHINE_FEATURE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(fitnessMachineFeatureCharacteristic)
        
        // Indoor Bike Data characteristic (notify)
        indoorBikeDataCharacteristic = BluetoothGattCharacteristic(
            BleUuids.INDOOR_BIKE_DATA_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val indoorBikeDataDescriptor = BluetoothGattDescriptor(
            BleUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        indoorBikeDataDescriptor.value = byteArrayOf(0x00, 0x00)
        indoorBikeDataCharacteristic!!.addDescriptor(indoorBikeDataDescriptor)
        service.addCharacteristic(indoorBikeDataCharacteristic!!)
        
        // Fitness Machine Status characteristic (notify)
        fitnessMachineStatusCharacteristic = BluetoothGattCharacteristic(
            BleUuids.FITNESS_MACHINE_STATUS_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val statusDescriptor = BluetoothGattDescriptor(
            BleUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        statusDescriptor.value = byteArrayOf(0x00, 0x00)
        fitnessMachineStatusCharacteristic!!.addDescriptor(statusDescriptor)
        service.addCharacteristic(fitnessMachineStatusCharacteristic!!)
        
        // Fitness Machine Control Point characteristic (write, indicate)
        fitnessMachineControlPointCharacteristic = BluetoothGattCharacteristic(
            BleUuids.FITNESS_MACHINE_CONTROL_POINT_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val controlPointDescriptor = BluetoothGattDescriptor(
            BleUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        controlPointDescriptor.value = byteArrayOf(0x00, 0x00)
        fitnessMachineControlPointCharacteristic!!.addDescriptor(controlPointDescriptor)
        service.addCharacteristic(fitnessMachineControlPointCharacteristic!!)
        
        // Supported Power Range characteristic (read-only)
        val supportedPowerRangeCharacteristic = BluetoothGattCharacteristic(
            BleUuids.SUPPORTED_POWER_RANGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(supportedPowerRangeCharacteristic)
        
        // Supported Resistance Level Range characteristic (read-only)
        val supportedResistanceRangeCharacteristic = BluetoothGattCharacteristic(
            BleUuids.SUPPORTED_RESISTANCE_LEVEL_RANGE_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(supportedResistanceRangeCharacteristic)
        
        return service
    }
    
    fun sendIndoorBikeData(ftmsData: FtmsData, notifyCallback: (BluetoothGattCharacteristic) -> Boolean) {
        indoorBikeDataCharacteristic?.let { characteristic ->
            try {
                val data = ftmsData.toIndoorBikeDataBytes()
                characteristic.value = data
                Timber.d("Setting indoor bike data: ${data.size} bytes [${data.joinToString(",") { "%02x".format(it) }}], power=${ftmsData.instantaneousPower}, cadence=${ftmsData.instantaneousCadence}")
                
                val notificationResult = notifyCallback(characteristic)
                Timber.d("Notification result: $notificationResult")
            } catch (e: Exception) {
                Timber.e(e, "Error setting indoor bike data")
            }
        } ?: run {
            Timber.w("Indoor bike data characteristic is null, cannot send data")
        }
    }
    
    fun sendFitnessMachineStatus(statusData: ByteArray, notifyCallback: (BluetoothGattCharacteristic) -> Boolean) {
        fitnessMachineStatusCharacteristic?.let { characteristic ->
            characteristic.value = statusData
            notifyCallback(characteristic)
        }
    }
    
    fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic): Pair<ByteArray?, Int> {
        return when (characteristic.uuid) {
            BleUuids.FITNESS_MACHINE_FEATURE_UUID -> {
                // Return supported features for indoor bike
                val data = ByteArray(8)
                val features = FtmsConstants.INDOOR_BIKE_FEATURES
                val targetSettings = FtmsConstants.INDOOR_BIKE_TARGET_SETTINGS
                
                // Pack features (4 bytes) + target settings (4 bytes)
                data[0] = (features and 0xFF).toByte()
                data[1] = ((features shr 8) and 0xFF).toByte()
                data[2] = ((features shr 16) and 0xFF).toByte()
                data[3] = ((features shr 24) and 0xFF).toByte()
                data[4] = (targetSettings and 0xFF).toByte()
                data[5] = ((targetSettings shr 8) and 0xFF).toByte()
                data[6] = ((targetSettings shr 16) and 0xFF).toByte()
                data[7] = ((targetSettings shr 24) and 0xFF).toByte()
                
                Pair(data, BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.SUPPORTED_POWER_RANGE_UUID -> {
                // Min power (0W), Max power (2000W), Min increment (1W)
                val data = byteArrayOf(0, 0, 0xD0.toByte(), 0x07, 1, 0)
                Pair(data, BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.SUPPORTED_RESISTANCE_LEVEL_RANGE_UUID -> {
                // Min resistance (0), Max resistance (100), Min increment (1)
                val data = byteArrayOf(0, 0, 100, 0, 1, 0)
                Pair(data, BluetoothGatt.GATT_SUCCESS)
            }
            else -> {
                Timber.w("Unknown FTMS characteristic read: ${characteristic.uuid}")
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
            BleUuids.FITNESS_MACHINE_CONTROL_POINT_UUID -> {
                // Handle control point commands
                if (value != null && value.isNotEmpty()) {
                    handleControlPointCommand(device, value[0], gattServer)
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
    
    private fun handleControlPointCommand(device: BluetoothDevice?, opCode: Byte, gattServer: BluetoothGattServer?) {
        val response = when (opCode) {
            FtmsConstants.FTMS_CONTROL_REQUEST_CONTROL -> {
                Timber.d("Control requested by ${device?.address}")
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            FtmsConstants.FTMS_CONTROL_START_OR_RESUME -> {
                Timber.d("Start/Resume requested by ${device?.address}")
                // Send status notification would be handled by the server manager
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            FtmsConstants.FTMS_CONTROL_STOP_OR_PAUSE -> {
                Timber.d("Stop/Pause requested by ${device?.address}")
                // Send status notification would be handled by the server manager
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            FtmsConstants.FTMS_CONTROL_RESET -> {
                Timber.d("Reset requested by ${device?.address}")
                // Send status notification would be handled by the server manager
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_SUCCESS)
            }
            else -> {
                Timber.d("Unsupported op code: $opCode from ${device?.address}")
                byteArrayOf(0x80.toByte(), opCode, FtmsConstants.FTMS_RESPONSE_OP_CODE_NOT_SUPPORTED)
            }
        }
        
        // Send indication response
        fitnessMachineControlPointCharacteristic?.let { characteristic ->
            characteristic.value = response
            try {
                device?.let {
                    gattServer?.notifyCharacteristicChanged(it, characteristic, true)
                }
            } catch (e: SecurityException) {
                Timber.e(e, "Security exception when sending indication")
            }
        }
    }
}
