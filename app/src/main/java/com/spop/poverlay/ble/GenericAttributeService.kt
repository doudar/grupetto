package com.spop.poverlay.ble

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice

@Suppress("DEPRECATION")
class GenericAttributeService(server: BleServer) : BaseBleService(server) {

    private val serviceChangedCharacteristic = BluetoothGattCharacteristic(
        GenericAttributeConstants.ServiceChangedUUID,
        BluetoothGattCharacteristic.PROPERTY_INDICATE,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                GenericAttributeConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
        // Full range by default; clients should re-discover all services.
        setValue(byteArrayOf(0x01, 0x00, 0xFF.toByte(), 0xFF.toByte()))
    }

    override val service = BluetoothGattService(
        GenericAttributeConstants.ServiceUUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(serviceChangedCharacteristic)
    }

    override fun onConnected(device: BluetoothDevice) {
        super.onConnected(device)
        // Best-effort indication to trigger service rediscovery.
        server.notifyCharacteristicChanged(device, serviceChangedCharacteristic, true)
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        super.onDescriptorWriteRequest(
            device,
            requestId,
            descriptor,
            preparedWrite,
            responseNeeded,
            offset,
            value
        )
        if (descriptor.uuid == GenericAttributeConstants.ClientCharacteristicConfigurationUUID) {
            // If the client enables indications, immediately send Service Changed.
            server.notifyCharacteristicChanged(device, serviceChangedCharacteristic, true)
        }
    }

    override fun onSensorDataUpdated(
        cadence: Float,
        power: Float,
        speed: Float,
        resistance: Float
    ) {
        // No-op for GATT service.
    }
}
