package com.spop.poverlay.ble

import android.bluetooth.*
import com.spop.poverlay.ble.BleUuids
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * FTMS Service Implementation
 * Handles Fitness Machine Service (FTMS) BLE characteristics and data formatting
 */
class FtmsServiceImpl {
    
    private var indoorBikeDataCharacteristic: BluetoothGattCharacteristic? = null
    private var fitnessMachineStatusCharacteristic: BluetoothGattCharacteristic? = null
    
    // FTMS counters and state
    private var totalDistance = 0f
    private var totalEnergy = 0f
    
    /**
     * FTMS Data Container
     * Contains all fitness machine data points for transmission over BLE
     */
    data class FtmsData(
        val instantaneousPower: Int = 0,        // watts
        val instantaneousCadence: Float = 0f,   // RPM
        val instantaneousSpeed: Float = 0f,     // m/s
        val totalDistance: Int = 0,             // meters
        val elapsedTime: Int = 0,               // seconds
        val heartRate: Int = 0,                 // BPM
        val resistanceLevel: Float = 0f,        // resistance level
        val totalEnergy: Int = 0,               // kilojoules
        val energyPerHour: Int = 0,             // watts
        val energyPerMinute: Int = 0            // watts
    ) {
        companion object {
            const val SPEED_RESOLUTION = 0.01f      // Speed resolution in m/s
            const val CADENCE_RESOLUTION = 0.5f     // Cadence resolution in RPM
            const val DISTANCE_RESOLUTION = 1       // Distance resolution in meters
        }

        /**
         * Convert to Indoor Bike Data characteristic bytes (0x2AD2)
         * Based on FTMS specification for Indoor Bike Data
         */
        fun toIndoorBikeDataBytes(): ByteArray {
            val buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
            
            // Flags field (2 bytes) - indicates which data fields are present
            var flags = 0x0044 // More Data = 0, Average Speed = 0, Instantaneous Cadence = 1, Total Distance = 1, Resistance Level = 0, Instantaneous Power = 1
            if (heartRate > 0) {
                flags = flags or 0x0400 // Heart Rate = 1
            }
            if (totalEnergy > 0) {
                flags = flags or 0x1000 // Expended Energy = 1
            }
            if (elapsedTime > 0) {
                flags = flags or 0x0800 // Elapsed Time = 1
            }
            if (resistanceLevel > 0) {
                flags = flags or 0x0080 // Resistance Level = 1
            }
            
            buffer.putShort(flags.toShort())
            
            // Instantaneous Speed (2 bytes) - resolution 0.01 km/h
            val speedKmh = (instantaneousSpeed * 3.6f / SPEED_RESOLUTION).roundToInt()
            buffer.putShort(speedKmh.toShort())
            
            // Instantaneous Cadence (2 bytes) - resolution 0.5 RPM
            val cadenceScaled = (instantaneousCadence / CADENCE_RESOLUTION).roundToInt()
            buffer.putShort(cadenceScaled.toShort())
            
            // Instantaneous Power (2 bytes) - resolution 1 watt
            buffer.putShort(instantaneousPower.toShort())
            
            // Total Distance (3 bytes) - resolution 1 meter
            val distanceBytes = totalDistance and 0xFFFFFF
            buffer.put((distanceBytes and 0xFF).toByte())
            buffer.put(((distanceBytes shr 8) and 0xFF).toByte())
            buffer.put(((distanceBytes shr 16) and 0xFF).toByte())
            
            // Resistance Level (2 bytes) - unitless, resolution 1
            if (resistanceLevel > 0) {
                buffer.putShort(resistanceLevel.roundToInt().toShort())
            }
            
            // Heart Rate (1 byte) - resolution 1 BPM
            if (heartRate > 0) {
                buffer.put(heartRate.toByte())
            }
            
            // Elapsed Time (2 bytes) - resolution 1 second
            if (elapsedTime > 0) {
                buffer.putShort(elapsedTime.toShort())
            }
            
            // Expended Energy (2 bytes) - resolution 1 kilojoule
            if (totalEnergy > 0) {
                buffer.putShort(totalEnergy.toShort())
            }
            
            // Create the final byte array with only the used bytes
            val result = ByteArray(buffer.position())
            buffer.flip()
            buffer.get(result)
            
            return result
        }
    }
    
    /**
     * Create the FTMS BLE service with all required characteristics
     */
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
        val fitnessMachineStatusDescriptor = BluetoothGattDescriptor(
            BleUuids.CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        fitnessMachineStatusDescriptor.value = byteArrayOf(0x00, 0x00)
        fitnessMachineStatusCharacteristic!!.addDescriptor(fitnessMachineStatusDescriptor)
        service.addCharacteristic(fitnessMachineStatusCharacteristic!!)
        
        return service
    }
    
    /**
     * Send Indoor Bike Data to connected BLE devices
     */
    fun sendIndoorBikeData(ftmsData: FtmsData, notifyCallback: (BluetoothGattCharacteristic) -> Boolean) {
        indoorBikeDataCharacteristic?.let { characteristic ->
            try {
                val data = ftmsData.toIndoorBikeDataBytes()
                characteristic.value = data
                Timber.d("Setting indoor bike data: ${data.size} bytes, power=${ftmsData.instantaneousPower}, cadence=${ftmsData.instantaneousCadence}")
                
                notifyCallback(characteristic)
            } catch (e: Exception) {
                Timber.e(e, "Error setting indoor bike data")
            }
        } ?: run {
            Timber.w("Indoor bike data characteristic is null, cannot send data")
        }
    }
    
    /**
     * Send Fitness Machine Status to connected BLE devices
     */
    fun sendFitnessMachineStatus(statusData: ByteArray, notifyCallback: (BluetoothGattCharacteristic) -> Boolean) {
        fitnessMachineStatusCharacteristic?.let { characteristic ->
            try {
                characteristic.value = statusData
                notifyCallback(characteristic)
            } catch (e: Exception) {
                Timber.e(e, "Error setting fitness machine status")
            }
        }
    }
    
    /**
     * Handle read requests for FTMS characteristics
     */
    fun handleCharacteristicRead(characteristic: BluetoothGattCharacteristic): Pair<ByteArray?, Int> {
        return when (characteristic.uuid) {
            BleUuids.FITNESS_MACHINE_FEATURE_UUID -> {
                // Return supported features for FTMS
                val data = byteArrayOf(0x83.toByte(), 0x14.toByte(), 0x00.toByte(), 0x00.toByte(),
                                     0x0C.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())
                Pair(data, BluetoothGatt.GATT_SUCCESS)
            }
            else -> {
                Pair(null, BluetoothGatt.GATT_READ_NOT_PERMITTED)
            }
        }
    }
    
    /**
     * Handle write requests for FTMS characteristics
     */
    fun handleCharacteristicWrite(
        device: BluetoothDevice?,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray?,
        gattServer: BluetoothGattServer?
    ): Int {
        // FTMS characteristics are typically read-only or notify-only
        // Control Point characteristic handling could be added here if needed
        return BluetoothGatt.GATT_WRITE_NOT_PERMITTED
    }
    
    /**
     * Reset FTMS counters and state
     */
    fun reset() {
        totalDistance = 0f
        totalEnergy = 0f
    }
}
