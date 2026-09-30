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
    private val deviceType: DeviceType = DeviceType.Bike,
    private val control: com.spop.poverlay.control.BikeControl? = null
) : BaseBleService(server) {

    private val isTread = deviceType == DeviceType.Tread
    private val canControl = !isTread && control?.supported == true
    private var lastPermissionLostCount = control?.state?.value?.permissionLostCount ?: 0

    fun onControlStateChanged(state: com.spop.poverlay.control.ControlState) {
        if (!canControl) return
        if (lastPermissionLostCount != state.permissionLostCount) {
            machineStatusCharacteristic.value = byteArrayOf(0xff.toByte())
            server.notifyDirConCharacteristicChanged(machineStatusCharacteristic)
            connectedDevices.toList().forEach { server.notifyCharacteristicChanged(it, machineStatusCharacteristic, false) }
        }
        lastPermissionLostCount = state.permissionLostCount
    }

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
    val targetFlags = if (canControl) (1 shl 2) or (1 shl 3) or (1 shl 13) else 0

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

    private val machineStatusCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.MachineStatusUUID, BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(BluetoothGattDescriptor(FitnessMachineConstants.ClientCharacteristicConfigurationUUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
    }
    private val powerRangeCharacteristic = BluetoothGattCharacteristic(
        FitnessMachineConstants.SupportedPowerRangeUUID, BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply { value = byteArrayOf(25, 0, 0xe8.toByte(), 3, 1, 0) }

    fun handleControl(client: String, value: ByteArray): ByteArray = synchronized(server) {
        val opcode = value.firstOrNull()?.toInt()?.and(255) ?: 0
        val reply = if (canControl) control!!.procedure(client, value)
        else com.spop.poverlay.control.BikeControl.Reply(
            if (value.size == 1 && opcode in listOf(0, 1, 7) ||
                value.size == 2 && opcode == 8 && value[1].toInt() in 1..2) 1 else 2)
        reply.status?.let { status ->
            machineStatusCharacteristic.value = status
            server.notifyDirConCharacteristicChanged(machineStatusCharacteristic)
            connectedDevices.toList().forEach { server.notifyCharacteristicChanged(it, machineStatusCharacteristic, false) }
        }
        if (reply.result == 1 && opcode in listOf(1, 7, 8)) {
            trainingStatusCharacteristic.value = byteArrayOf(0,
                (if (opcode == 7) FitnessMachineConstants.TrainingStatus.ManualMode
                else FitnessMachineConstants.TrainingStatus.Idle).toByte())
            server.notifyDirConCharacteristicChanged(trainingStatusCharacteristic)
            connectedDevices.forEach { server.notifyCharacteristicChanged(it, trainingStatusCharacteristic, false) }
        }
        byteArrayOf(0x80.toByte(), opcode.toByte(), reply.result.toByte())
    }

    override fun onDisconnected(device: BluetoothDevice) {
        control?.disconnect("ble:${device.address}")
        super.onDisconnected(device)
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
        if (canControl) {
            supportedResistanceRangeCharacteristic.value = byteArrayOf(0, 0, 0xe8.toByte(), 3, 10, 0)
            addCharacteristic(machineStatusCharacteristic)
            addCharacteristic(powerRangeCharacteristic)
        }
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
            if (preparedWrite || offset != 0 || value == null || value.isEmpty()) {
                if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            if (responseNeeded) server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            controlPointCharacteristic.value = handleControl("ble:${device.address}", value)
            server.notifyCharacteristicChanged(device, controlPointCharacteristic, true)
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

        val newStatus = when {
            cadence <= 0 -> FitnessMachineConstants.TrainingStatus.Idle
            canControl && control?.state?.value?.mode == com.spop.poverlay.control.ControlMode.Erg -> FitnessMachineConstants.TrainingStatus.WattControl
            else -> FitnessMachineConstants.TrainingStatus.ManualMode
        }.toByte()
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
