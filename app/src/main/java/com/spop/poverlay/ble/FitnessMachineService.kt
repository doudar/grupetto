package com.spop.poverlay.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import com.spop.poverlay.erg.ErgController
import com.spop.poverlay.sensor.interfaces.SensorInterface
import timber.log.Timber

@Suppress("DEPRECATION")
class FitnessMachineService(
    server: BleServer,
    private val ergController: ErgController,
    private val sensorInterface: SensorInterface
) : BaseBleService(server) {

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

    private val featureCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.FeatureUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // 8-byte payload: 4 bytes FeatureFlags + 4 bytes TargetFlags (LE)
        val featureFlags =
            FitnessMachineConstants.FeatureFlags.CadenceSupported or
            FitnessMachineConstants.FeatureFlags.PowerMeasurementSupported or
            FitnessMachineConstants.FeatureFlags.ResistanceLevelSupported

        val targetFlags =
            FitnessMachineConstants.FitnessMachineTargetFlags.ResistanceTargetSettingSupported or
            FitnessMachineConstants.FitnessMachineTargetFlags.PowerTargetSettingSupported

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
        // FTMS uses 0.1 resolution. Peloton range 0-100 maps to 0-1000 in FTMS units.
        // Little-endian sint16: min=0 (0%), max=1000 (100%), step=10 (1%)
        setValue(byteArrayOf(
            0x00, 0x00,             // min = 0
            0xE8.toByte(), 0x03,    // max = 1000
            0x0A, 0x00              // step = 10
        ))
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

    private val fitnessMachineStatusCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.FitnessMachineStatusUUID,
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

    private val supportedPowerRangeCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.SupportedPowerRangeUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // Little-endian: min(25W), max(1000W), step(1W) -> sint16 values
        setValue(byteArrayOf(
            0x19, 0x00,       // min = 25
            0xE8.toByte(), 0x03, // max = 1000
            0x01, 0x00        // step = 1
        ))
    }

    override val service = BluetoothGattService(
        FitnessMachineConstants.ServiceUUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(indoorBikeDataCharacteristic)
        addCharacteristic(featureCharacteristic)
        addCharacteristic(controlPointCharacteristic)
        addCharacteristic(supportedResistanceRangeCharacteristic)
        addCharacteristic(supportedPowerRangeCharacteristic)
        addCharacteristic(trainingStatusCharacteristic)
        addCharacteristic(fitnessMachineStatusCharacteristic)
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
            Timber.d("FTMS Control Point write: opcode=0x%02X, value=%s", opcode,
                value?.joinToString(",") { "0x%02X".format(it) } ?: "null")
            val result: Int

            when (opcode) {
                FitnessMachineConstants.FitnessMachineControlPointProcedure.RequestControl -> {
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.Reset -> {
                    ergController.disable()
                    setTrainingStatus(FitnessMachineConstants.TrainingStatus.Idle)
                    notifyFitnessMachineStatus(
                        byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.Reset.toByte())
                    )
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.StartOrResume -> {
                    setTrainingStatus(FitnessMachineConstants.TrainingStatus.ManualMode)
                    notifyFitnessMachineStatus(
                        byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.StartedOrResumedByUser.toByte())
                    )
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.StopOrPause -> {
                    ergController.disable()
                    setTrainingStatus(FitnessMachineConstants.TrainingStatus.Idle)
                    notifyFitnessMachineStatus(
                        byteArrayOf(FitnessMachineConstants.FitnessMachineStatus.StoppedOrPausedByUser.toByte())
                    )
                    result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.SetTargetResistanceLevel -> {
                    // sint16 in 0.1 units (e.g. 500 = 50.0%)
                    if (value != null && value.size >= 3) {
                        val raw = (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
                        val resistancePercent = (raw.toShort().toInt() / 10).coerceIn(0, 100)
                        ergController.disable()
                        sensorInterface.setResistance(resistancePercent)
                        Timber.d("FTMS SetTargetResistanceLevel: raw=$raw -> $resistancePercent%")
                        notifyFitnessMachineStatus(byteArrayOf(
                            FitnessMachineConstants.FitnessMachineStatus.TargetResistanceLevelChanged.toByte(),
                            value[1], value[2]
                        ))
                        result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                    } else {
                        result = FitnessMachineConstants.FitnessMachineControlPointResultCode.InvalidParameter
                    }
                }
                FitnessMachineConstants.FitnessMachineControlPointProcedure.SetTargetPower -> {
                    // sint16 watts
                    if (value != null && value.size >= 3) {
                        val watts = ((value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)).toShort().toInt()
                        Timber.d("FTMS SetTargetPower: ${watts}W")
                        if (ergController.isActive()) {
                            ergController.setTargetPower(watts)
                        } else {
                            ergController.enable(watts)
                        }
                        setTrainingStatus(FitnessMachineConstants.TrainingStatus.WattControl)
                        notifyFitnessMachineStatus(byteArrayOf(
                            FitnessMachineConstants.FitnessMachineStatus.TargetPowerChanged.toByte(),
                            value[1], value[2]
                        ))
                        result = FitnessMachineConstants.FitnessMachineControlPointResultCode.Success
                    } else {
                        result = FitnessMachineConstants.FitnessMachineControlPointResultCode.InvalidParameter
                    }
                }
                else -> {
                    Timber.w("FTMS Control Point: unsupported opcode 0x%02X", opcode)
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

    override fun onDisconnected(device: BluetoothDevice) {
        super.onDisconnected(device)
        if (connectedDevices.isEmpty()) {
            ergController.disable()
            Timber.d("Last FTMS client disconnected, ERG disabled")
        }
    }

    private fun setTrainingStatus(status: Int) {
        trainingStatusCharacteristic.setValue(byteArrayOf(0x00, status.toByte()))
        for (d in connectedDevices) {
            server.notifyCharacteristicChanged(d, trainingStatusCharacteristic, false)
        }
    }

    private fun notifyFitnessMachineStatus(statusValue: ByteArray) {
        fitnessMachineStatusCharacteristic.setValue(statusValue)
        for (d in connectedDevices) {
            server.notifyCharacteristicChanged(d, fitnessMachineStatusCharacteristic, false)
        }
    }

    override fun onSensorDataUpdated(cadence: Float, power: Float, speed: Float, resistance: Float) {
        // Build 16-bit flags (LE when serialized). MoreData bit (0) is intentionally 0.
        val flags = FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousCadencePresent or
            FitnessMachineConstants.IndoorBikeDataFlags.InstantaneousPowerPresent or
            FitnessMachineConstants.IndoorBikeDataFlags.ResistanceLevelPresent

        val speedValue = (speed * 100).toInt()
        val cadenceValue = (cadence * 2).toInt()
        val powerValue = power.toInt()
        val resistanceValue = resistance.toInt()

        indoorBikeDataCharacteristic.setValue(byteArrayOf(
            (flags and 0xFF).toByte(),
            (flags shr 8 and 0xFF).toByte(),
            (speedValue and 0xFF).toByte(),
            (speedValue shr 8 and 0xFF).toByte(),
            (cadenceValue and 0xFF).toByte(),
            (cadenceValue shr 8 and 0xFF).toByte(),
            (resistanceValue and 0xFF).toByte(),
            (resistanceValue shr 8 and 0xFF).toByte(),
            (powerValue and 0xFF).toByte(),
            (powerValue shr 8 and 0xFF).toByte()
        ))

        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, indoorBikeDataCharacteristic, false)
        }

        val newStatus = when {
            ergController.isActive() && cadence > 0 -> FitnessMachineConstants.TrainingStatus.WattControl.toByte()
            cadence > 0 -> FitnessMachineConstants.TrainingStatus.ManualMode.toByte()
            else -> FitnessMachineConstants.TrainingStatus.Idle.toByte()
        }
        // Keep the two-byte layout consistent when updating
        val currentStatus = trainingStatusCharacteristic.getValue()
        if (currentStatus == null || currentStatus.size < 2 || currentStatus[1] != newStatus) {
            trainingStatusCharacteristic.setValue(byteArrayOf(0x00, newStatus))
            for (device in connectedDevices) {
                server.notifyCharacteristicChanged(device, trainingStatusCharacteristic, false)
            }
        }
    }
}
