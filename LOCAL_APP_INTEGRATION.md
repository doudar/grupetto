# Local App Integration Guide

This guide explains how to integrate your Android fitness app with grupetto when both are running on the same Peloton device.

## Overview

Grupetto broadcasts Bluetooth LE (BLE) GATT services that emulate standard cycling sensors:
- **Cycling Power Service** (UUID: `0x1818`) - Power meter data
- **Cycling Speed and Cadence Service** (UUID: `0x1816`) - Speed and cadence data  
- **Fitness Machine Service** (UUID: `0x1826`) - Complete indoor trainer/bike data
- **Device Information Service** (UUID: `0x180A`) - Device metadata

## Prerequisites

1. Grupetto must be installed and running on the Peloton
2. **Local Mode must be enabled** in grupetto's settings
3. Your app must have Bluetooth permissions:
   - `android.permission.BLUETOOTH`
   - `android.permission.BLUETOOTH_ADMIN`
   - `android.permission.ACCESS_FINE_LOCATION`
   - For Android 12+: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`

## Discovering Grupetto's BLE Services

### Device Name
Look for a device named **"Grupetto FTMS"** (or the custom name set in grupetto's settings).

### Service UUIDs
When Local Mode is enabled, grupetto advertises with the Cycling Power Service UUID (`0x1818`) prominently in the advertisement data.

### Example: Scanning for Grupetto

```kotlin
val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
val bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

val scanCallback = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
        val device = result.device
        val deviceName = result.scanRecord?.deviceName
        
        if (deviceName?.contains("Grupetto", ignoreCase = true) == true) {
            // Found grupetto! Connect to it
            connectToGrupetto(device)
        }
    }
}

// Scan for devices advertising Cycling Power Service
val scanFilter = ScanFilter.Builder()
    .setServiceUuid(ParcelUuid(UUID.fromString("00001818-0000-1000-8000-00805f9b34fb")))
    .build()

val scanSettings = ScanSettings.Builder()
    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
    .build()

bluetoothLeScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
```

## Connecting to Grupetto

Once you've discovered the grupetto device, connect to it as a GATT client:

```kotlin
fun connectToGrupetto(device: BluetoothDevice) {
    val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Connected! Discover services
                gatt.discoverServices()
            }
        }
        
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Find and subscribe to characteristics
                setupCyclingPowerService(gatt)
                setupCyclingSpeedAndCadenceService(gatt)
            }
        }
        
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Process sensor data updates
            when (characteristic.uuid.toString()) {
                "00002a63-0000-1000-8000-00805f9b34fb" -> {
                    // Cycling Power Measurement
                    val power = parsePowerMeasurement(characteristic.value)
                    onPowerUpdate(power)
                }
                "00002a5b-0000-1000-8000-00805f9b34fb" -> {
                    // CSC Measurement
                    val (cadence, speed) = parseCSCMeasurement(characteristic.value)
                    onCadenceUpdate(cadence)
                    onSpeedUpdate(speed)
                }
            }
        }
    }
    
    device.connectGatt(context, false, gattCallback)
}
```

## Reading Sensor Data

### Cycling Power Service (0x1818)

**Cycling Power Measurement Characteristic** (`0x2A63`):
```kotlin
fun parsePowerMeasurement(data: ByteArray): Int {
    val flags = data[0].toInt() and 0xFF
    val hasCrankRevolution = (flags and 0x20) != 0
    
    // Instantaneous Power (Watts) - always at bytes 2-3
    val power = ((data[3].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
    
    return power
}
```

### Cycling Speed and Cadence Service (0x1816)

**CSC Measurement Characteristic** (`0x2A5B`):
```kotlin
fun parseCSCMeasurement(data: ByteArray): Pair<Float, Float> {
    val flags = data[0].toInt() and 0xFF
    val hasWheelRev = (flags and 0x01) != 0
    val hasCrankRev = (flags and 0x02) != 0
    
    var offset = 1
    var wheelRPM = 0f
    var crankRPM = 0f
    
    if (hasWheelRev) {
        // Cumulative Wheel Revolutions (4 bytes)
        // Last Wheel Event Time (2 bytes, 1/1024 second resolution)
        offset += 6
    }
    
    if (hasCrankRev) {
        val crankRevs = ((data[offset + 1].toInt() and 0xFF) shl 8) or 
                        (data[offset].toInt() and 0xFF)
        val crankTime = ((data[offset + 3].toInt() and 0xFF) shl 8) or 
                        (data[offset + 2].toInt() and 0xFF)
        
        // Calculate cadence from time deltas (requires previous values)
        crankRPM = calculateCadence(crankRevs, crankTime)
    }
    
    return Pair(crankRPM, wheelRPM)
}
```

### Fitness Machine Service (0x1826)

For a complete indoor bike profile, subscribe to the **Indoor Bike Data Characteristic** (`0x2AD2`):

```kotlin
fun parseIndoorBikeData(data: ByteArray): BikeData {
    val flags = ((data[1].toInt() and 0xFF) shl 8) or (data[0].toInt() and 0xFF)
    var offset = 2
    
    val bikeData = BikeData()
    
    // Check flags to determine which fields are present
    if ((flags and 0x0001) != 0) {
        // More data - read speed
        bikeData.speed = ((data[offset + 1].toInt() and 0xFF) shl 8) or 
                         (data[offset].toInt() and 0xFF)
        offset += 2
    }
    
    if ((flags and 0x0002) == 0) {
        // Average speed present
        offset += 2
    }
    
    if ((flags and 0x0004) != 0) {
        // Instantaneous Cadence present
        bikeData.cadence = ((data[offset + 1].toInt() and 0xFF) shl 8) or 
                           (data[offset].toInt() and 0xFF)
        offset += 2
    }
    
    if ((flags and 0x0040) != 0) {
        // Instantaneous Power present
        bikeData.power = ((data[offset + 1].toInt() and 0xFF) shl 8) or 
                         (data[offset].toInt() and 0xFF)
        offset += 2
    }
    
    return bikeData
}

data class BikeData(
    var speed: Int = 0,      // km/h * 100
    var cadence: Int = 0,    // RPM * 2
    var power: Int = 0,      // Watts
    var resistance: Int = 0  // Level
)
```

## Subscribing to Notifications

To receive real-time updates, enable notifications on the measurement characteristics:

```kotlin
fun setupCyclingPowerService(gatt: BluetoothGatt) {
    val powerService = gatt.getService(UUID.fromString("00001818-0000-1000-8000-00805f9b34fb"))
    val powerMeasurement = powerService?.getCharacteristic(
        UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
    )
    
    powerMeasurement?.let { characteristic ->
        // Enable notifications
        gatt.setCharacteristicNotification(characteristic, true)
        
        // Write to the Client Characteristic Configuration Descriptor
        val descriptor = characteristic.getDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        )
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gatt.writeDescriptor(descriptor)
    }
}
```

## Troubleshooting

### Can't find the device
1. Ensure Local Mode is enabled in grupetto
2. Check that BLE TX is enabled in grupetto
3. Verify Bluetooth permissions in your app
4. Try restarting the grupetto overlay
5. Check that your app is scanning with the correct service UUID filter

### Connection drops
- The Peloton's Bluetooth can be unstable with multiple connections
- Implement reconnection logic in your app
- Avoid having too many concurrent BLE connections

### No data updates
1. Verify you've subscribed to notifications (written to CCCD)
2. Check that the Peloton bike is actually generating data (pedaling)
3. Enable Bluetooth HCI logs on Android to debug characteristic updates

## Best Practices

1. **Reconnection Logic**: Implement automatic reconnection if the connection drops
2. **Resource Management**: Properly close GATT connections when your app goes to background
3. **Battery Optimization**: Use `SCAN_MODE_BALANCED` instead of `LOW_LATENCY` when user isn't actively searching
4. **Graceful Degradation**: Handle cases where only some services are available
5. **User Feedback**: Show clear connection status to users

## Example Integration

Check out popular fitness apps for reference:
- Zwift: Looks for Cycling Power Service and CSC Service
- MyWhoosh: Uses Fitness Machine Service for complete bike data
- TrainerRoad: Primarily uses Cycling Power Service

## Support

If you encounter issues integrating with grupetto:
1. Check that you're using the latest version of grupetto
2. Enable Local Mode in grupetto settings
3. Review the Bluetooth specification for the services you're using
4. Open an issue on the grupetto GitHub repository with details about your integration attempt

## References

- [Bluetooth Cycling Power Service Specification](https://www.bluetooth.com/specifications/specs/cycling-power-service-1-1/)
- [Bluetooth Cycling Speed and Cadence Service](https://www.bluetooth.com/specifications/specs/cycling-speed-and-cadence-service-1-1/)
- [Bluetooth Fitness Machine Service](https://www.bluetooth.com/specifications/specs/fitness-machine-service-1-0/)
