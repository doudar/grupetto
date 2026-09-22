package com.spop.poverlay.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService

@Suppress("DEPRECATION")
class CyclingSpeedAndCadenceService(server: BleServer) : BaseBleService(server) {

    // State for crank data
    private var cumulativeCrankRevolutions: Int = 0
    private var lastCrankEventTime1024: Int = 0 // uint16 in 1/1024s
    private var lastUpdateElapsedMs: Long = android.os.SystemClock.elapsedRealtime()
    private var crankFractionalRevs: Double = 0.0

    private val measurementCharacteristic = BluetoothGattCharacteristic(
        CyclingSpeedAndCadenceConstants.MeasurementUUID,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                CyclingSpeedAndCadenceConstants.ClientCharacteristicConfigurationUUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ
            )
        )
    }

    private val featureCharacteristic = BluetoothGattCharacteristic(
        CyclingSpeedAndCadenceConstants.FeatureUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        // Match C++ server: advertise wheel and crank support
        val flags = CyclingSpeedAndCadenceConstants.FeatureFlags.WheelRevolutionDataSupported or
                CyclingSpeedAndCadenceConstants.FeatureFlags.CrankRevolutionDataSupported
        setValue(
            byteArrayOf(
                (flags and 0xFF).toByte(),
                (flags shr 8 and 0xFF).toByte()
            )
        )
    }

    private val sensorLocationCharacteristic = BluetoothGattCharacteristic(
        CyclingSpeedAndCadenceConstants.SensorLocationUUID,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    ).apply {
        value = byteArrayOf(CyclingSpeedAndCadenceConstants.SensorLocation.RearWheel.toByte())
    }

    // Reused scratch buffer (max size: 1 flags + 6 wheel + 4 crank), to avoid allocating a
    // fresh ArrayList<Byte>/array every tick. setValue() still needs an exact-length array,
    // so a small copyOf() per call remains, but the boxed-Byte allocations are gone.
    private val measurementBuffer = ByteArray(11)

    override val service = BluetoothGattService(
        CyclingSpeedAndCadenceConstants.ServiceUUID,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(measurementCharacteristic)
        addCharacteristic(featureCharacteristic)
        addCharacteristic(sensorLocationCharacteristic)
    }

    override fun onSensorDataUpdated(cadence: Float, power: Float, speed: Float, resistance: Float) {
        // Build measurement from server's shared counters
        val hasWheel = server.cscLastWheelEvtTime != 0 || server.cscCumulativeWheelRev != 0L
        val hasCrank = server.cscLastCrankEvtTime != 0 || server.cscCumulativeCrankRev != 0

        var flags = 0
        if (hasWheel) flags = flags or CyclingSpeedAndCadenceConstants.MeasurementFlags.WheelRevolutionDataPresent
        if (hasCrank) flags = flags or CyclingSpeedAndCadenceConstants.MeasurementFlags.CrankRevolutionDataPresent

        var offset = 0
        measurementBuffer[offset++] = flags.toByte()
        if (hasWheel) {
            val wheelRevs = server.cscCumulativeWheelRev
            val wheelTime = server.cscLastWheelEvtTime
            measurementBuffer[offset++] = (wheelRevs and 0xFF).toByte()
            measurementBuffer[offset++] = ((wheelRevs shr 8) and 0xFF).toByte()
            measurementBuffer[offset++] = ((wheelRevs shr 16) and 0xFF).toByte()
            measurementBuffer[offset++] = ((wheelRevs shr 24) and 0xFF).toByte()
            measurementBuffer[offset++] = (wheelTime and 0xFF).toByte()
            measurementBuffer[offset++] = ((wheelTime shr 8) and 0xFF).toByte()
        }
        if (hasCrank) {
            val crankRevs = server.cscCumulativeCrankRev
            val crankTime = server.cscLastCrankEvtTime
            measurementBuffer[offset++] = (crankRevs and 0xFF).toByte()
            measurementBuffer[offset++] = ((crankRevs shr 8) and 0xFF).toByte()
            measurementBuffer[offset++] = (crankTime and 0xFF).toByte()
            measurementBuffer[offset++] = ((crankTime shr 8) and 0xFF).toByte()
        }
        measurementCharacteristic.setValue(measurementBuffer.copyOf(offset))
        server.notifyDirConCharacteristicChanged(measurementCharacteristic)
        server.logBleDebug(
            "BLE CSC notify flags=0x${flags.toString(16)} wheelRev=${server.cscCumulativeWheelRev} " +
                "crankRev=${server.cscCumulativeCrankRev} payloadLen=$offset devices=${connectedDevices.size}"
        )

        for (device in connectedDevices) {
            server.notifyCharacteristicChanged(device, measurementCharacteristic, false)
        }
    }
}
