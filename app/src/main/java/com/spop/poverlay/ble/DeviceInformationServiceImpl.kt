package com.spop.poverlay.ble

import android.bluetooth.*
import timber.log.Timber

/**
 * Device Information Service Implementation
 * Handles the standard BLE Device Information Service
 */
class DeviceInformationServiceImpl(
    private val deviceName: String = "Grupetto FTMS"
) {
    
    fun createService(): BluetoothGattService {
        val service = BluetoothGattService(
            BleUuids.DEVICE_INFORMATION_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        
        // Manufacturer Name
        val manufacturerNameCharacteristic = BluetoothGattCharacteristic(
            BleUuids.MANUFACTURER_NAME_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        manufacturerNameCharacteristic.value = deviceName.toByteArray()
        service.addCharacteristic(manufacturerNameCharacteristic)
        
        // Model Number
        val modelNumberCharacteristic = BluetoothGattCharacteristic(
            BleUuids.MODEL_NUMBER_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        modelNumberCharacteristic.value = "Peloton FTMS Bridge".toByteArray()
        service.addCharacteristic(modelNumberCharacteristic)
        
        // Serial Number
        val serialNumberCharacteristic = BluetoothGattCharacteristic(
            BleUuids.SERIAL_NUMBER_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        serialNumberCharacteristic.value = "GRP-001".toByteArray()
        service.addCharacteristic(serialNumberCharacteristic)
        
        // Hardware Revision
        val hardwareRevisionCharacteristic = BluetoothGattCharacteristic(
            BleUuids.HARDWARE_REVISION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        hardwareRevisionCharacteristic.value = "1.0".toByteArray()
        service.addCharacteristic(hardwareRevisionCharacteristic)
        
        // Firmware Revision
        val firmwareRevisionCharacteristic = BluetoothGattCharacteristic(
            BleUuids.FIRMWARE_REVISION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        firmwareRevisionCharacteristic.value = "1.0.0".toByteArray()
        service.addCharacteristic(firmwareRevisionCharacteristic)
        
        // Software Revision
        val softwareRevisionCharacteristic = BluetoothGattCharacteristic(
            BleUuids.SOFTWARE_REVISION_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        softwareRevisionCharacteristic.value = "1.0.0".toByteArray()
        service.addCharacteristic(softwareRevisionCharacteristic)
        
        return service
    }
    
    fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic): Pair<ByteArray?, Int> {
        return when (characteristic.uuid) {
            BleUuids.MANUFACTURER_NAME_UUID -> {
                Pair(deviceName.toByteArray(), BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.MODEL_NUMBER_UUID -> {
                Pair("Peloton FTMS Bridge".toByteArray(), BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.SERIAL_NUMBER_UUID -> {
                Pair("GRP-001".toByteArray(), BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.HARDWARE_REVISION_UUID -> {
                Pair("1.0".toByteArray(), BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.FIRMWARE_REVISION_UUID -> {
                Pair("1.0.0".toByteArray(), BluetoothGatt.GATT_SUCCESS)
            }
            BleUuids.SOFTWARE_REVISION_UUID -> {
                Pair("1.0.0".toByteArray(), BluetoothGatt.GATT_SUCCESS)
            }
            else -> {
                Timber.w("Unknown Device Information characteristic read: ${characteristic.uuid}")
                Pair(null, BluetoothGatt.GATT_READ_NOT_PERMITTED)
            }
        }
    }
}
