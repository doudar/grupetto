package com.spop.poverlay.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.spop.poverlay.sensor.interfaces.DeviceType

@Suppress("DEPRECATION")
class FitnessMachineService(
    server: BleServer,
    private val deviceType: DeviceType = DeviceType.Bike
) : BaseBleService(server) {

    private val isTread = deviceType == DeviceType.Tread

    private val indoorBikeDataCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.IndoorBikeDataUUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    // Treadmill Data (0x2ACD) — used in place of Indoor Bike Data when this is a Tread.
    private val treadmillDataCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.TreadmillDataUUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    private val featureCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.FeatureUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // 8-byte payload: 4 bytes FeatureFlags + 4 bytes TargetFlags (LE)
        val featureFlags = if (isTread) {
            // Only advertise what we actually broadcast: instantaneous speed (implicit)
            // plus inclination (and its derived ramp angle).
            FitnessMachineConstants.FeatureFlags.InclinationSupported
        } else {
            FitnessMachineConstants.FeatureFlags.CadenceSupported or
            FitnessMachineConstants.FeatureFlags.PowerMeasurementSupported or
            FitnessMachineConstants.FeatureFlags.ResistanceLevelSupported
        }

    // No control supported -> all target flags 0
    val targetFlags = 0

        val payload = byteArrayOf(
            // Feature flags (uint32 LE)
            (featureFlags and 0xFF).toByte(),
            (featureFlags shr 8 and 0xFF).toByte(),
            (featureFlags shr 16 and 0xFF).toByte(),
            (featureFlags shr 24 and 0xFF).toByte(),
            // Target flags (uint32 LE)
            (targetFlags and 0xFF).toByte(),
            (targetFlags shr 8 and 0xFF).toByte(),
            (targetFlags shr 16 and 0xFF).toByte(),
            (targetFlags shr 24 and 0xFF).toByte()
        )
        setValue(payload)
    }

    private val controlPointCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.ControlPointUUID,
    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    private val supportedResistanceRangeCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.SupportedResistanceRangeUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        setValue(FitnessMachineData.supportedResistanceRange())
    }

    private val trainingStatusCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.TrainingStatusUUID,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // Common FTMS layout: first byte is additional info (0x00), second is status (Idle)
        setValue(byteArrayOf(0x00, FitnessMachineConstants.TrainingStatus.Idle.toByte()))
        addDescriptor(
            BluetoothGattDescriptor(
                FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    override val service = BluetoothGattService(
        FitnessMachineConstants.ServiceUUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        // A Fitness Machine exposes exactly one machine-type data characteristic.
        if (isTread) {
            addCharacteristic(treadmillDataCharacteristic)
        } else {
            addCharacteristic(indoorBikeDataCharacteristic)
        }
        addCharacteristic(featureCharacteristic)
        addCharacteristic(controlPointCharacteristic)
        if (!isTread) addCharacteristic(supportedResistanceRangeCharacteristic)
        addCharacteristic(trainingStatusCharacteristic)
    }

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ) {
        if (characteristic.uuid == FitnessMachineConstants.ControlPointUUID) {
            // Parse opcode
            val opcode = value?.getOrNull(0)?.toInt()?.and(0xFF) ?: -1
            val result: Int

            when (opcode) {
                FitnessMachineConstants.FitnessMachineControlPointProcedure.RequestControl -> {
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.Reset -> {
                    // Reset to Idle
                    trainingStatusCharacteristic.setValue(
                        byteArrayOf(0x00, FitnessMachineConstants.TrainingStatus.Idle.toByte())
                    )
                    server.notifyDirConCharacteristicChanged(trainingStatusCharacteristic)
                    for (d in connectedDevices) {
                        server.notifyCharacteristicChanged(d, trainingStatusCharacteristic, false)
                    }
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.StartOrResume -> {
                    // Move to ManualMode
                    trainingStatusCharacteristic.setValue(
                        byteArrayOf(0x00, FitnessMachineConstants.TrainingStatus.ManualMode.toByte())
                    )
                    server.notifyDirConCharacteristicChanged(trainingStatusCharacteristic)
                    for (d in connectedDevices) {
                        server.notifyCharacteristicChanged(d, trainingStatusCharacteristic, false)
                    }
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.StopOrPause -> {
                    // Move to Idle
                    trainingStatusCharacteristic.setValue(
                        byteArrayOf(0x00, FitnessMachineConstants.TrainingStatus.Idle.toByte())
                    )
                    server.notifyDirConCharacteristicChanged(trainingStatusCharacteristic)
                    for (d in connectedDevices) {
                        server.notifyCharacteristicChanged(d, trainingStatusCharacteristic, false)
                    }
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                else -> {
                    // Not supported
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.OpCodeNotSupported
                }
            }

            // Build Response Code indication: [0x80, requestOpCode, resultCode]
            val response = byteArrayOf(
                FitnessMachineConstants.FitnessMachineControlPointProcedure.ResponseCode.toByte(),
                (opcode.coerceAtLeast(0) and 0xFF).toByte(),
                (result and 0xFF).toByte()
            )
            controlPointCharacteristic.setValue(response)
            server.notifyDirConCharacteristicChanged(controlPointCharacteristic)
            // FTMS mandates indications for Control Point
            server.notifyCharacteristicChanged(device, controlPointCharacteristic, true)

            if (responseNeeded) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            return
        }

        // Default behavior for other characteristics
        super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
    }

    override fun onSensorDataUpdated(cadence: Float, power: Float, speed: Float, resistance: Float, incline: Float) {
        if (isTread) {
            onTreadDataUpdated(speed, incline)
            return
        }
        indoorBikeDataCharacteristic.setValue(
            FitnessMachineData.encode(cadence, power, speed, resistance)
        )
        server.notifyDirConCharacteristicChanged(indoorBikeDataCharacteristic)

        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, indoorBikeDataCharacteristic, false)
        }

        val newStatus = if (cadence > 0) FitnessMachineConstants.TrainingStatus.ManualMode.toByte() else FitnessMachineConstants.TrainingStatus.Idle.toByte()
        // Keep the two-byte layout consistent when updating
        val currentStatus = trainingStatusCharacteristic.getValue()
        if (currentStatus == null || currentStatus.size < 2 || currentStatus[1] != newStatus) {
            trainingStatusCharacteristic.setValue(byteArrayOf(0x00, newStatus))
            server.notifyDirConCharacteristicChanged(trainingStatusCharacteristic)
            for (device in connectedDevices) {
                server.notifyCharacteristicChanged(device, trainingStatusCharacteristic, false)
            }
        }
    }

    // Packs and notifies FTMS Treadmill Data (0x2ACD). Speed arrives in mph and is
    // converted to km/h (FTMS is metric); incline is a percent grade. Mirrors the
    // bike packer's notify path.
    private fun onTreadDataUpdated(speedMph: Float, incline: Float) {
        val speedKmh = speedMph * 1.60934f
        treadmillDataCharacteristic.setValue(
            FitnessMachineConstants.buildTreadmillDataPacket(speedKmh, incline)
        )
        server.notifyDirConCharacteristicChanged(treadmillDataCharacteristic)
        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, treadmillDataCharacteristic, false)
        }

        val newStatus = if (speedMph > 0) FitnessMachineConstants.TrainingStatus.ManualMode.toByte() else FitnessMachineConstants.TrainingStatus.Idle.toByte()
        val currentStatus = trainingStatusCharacteristic.getValue()
        if (currentStatus == null || currentStatus.size < 2 || currentStatus[1] != newStatus) {
            trainingStatusCharacteristic.setValue(byteArrayOf(0x00, newStatus))
            server.notifyDirConCharacteristicChanged(trainingStatusCharacteristic)
            for (device in connectedDevices) {
                server.notifyCharacteristicChanged(device, trainingStatusCharacteristic, false)
            }
        }
    }
}
