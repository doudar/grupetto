# BLE FTMS Integration for Grupetto

This integration adds Bluetooth Low Energy Fitness Machine Service (FTMS) capability to the Grupetto Peloton overlay app, allowing the bike data to be transmitted wirelessly to fitness apps like Zwift, TrainerRoad, and others.

## Features

- **FTMS Indoor Bike Profile**: Implements the standard Bluetooth FTMS indoor bike profile
- **Real-time Data Transmission**: Broadcasts power, cadence, speed, and resistance data from the Peloton bike
- **Multiple Device Support**: Can connect to multiple fitness apps simultaneously
- **Configurable Device Name**: Set a custom Bluetooth device name
- **Persistent Service**: Runs as a foreground service for reliable data transmission

## How it Works

1. The `BleFtmsService` starts as a foreground service when enabled
2. It creates a `BleServer` that sets up a Bluetooth GATT server
3. The server advertises the Fitness Machine Service (UUID: 0x1826)
4. Data from the Peloton sensor interfaces is converted to FTMS format
5. Connected apps receive notifications with updated bike data every second

## Configuration

- **Enable BLE FTMS**: Toggle in the main configuration screen
- **Device Name**: Customize the Bluetooth device name (default: "Grupetto")

## Supported FTMS Characteristics

- **Fitness Machine Feature** (0x2ACC): Advertises supported features
- **Indoor Bike Data** (0x2AD2): Real-time bike data (power, cadence, speed, resistance)
- **Fitness Machine Status** (0x2ADA): Machine status updates
- **Fitness Machine Control Point** (0x2AD9): Control commands from apps
- **Supported Power Range** (0x2AD8): Power measurement range (0-2000W)
- **Supported Resistance Range** (0x2AD6): Resistance range (0-100)

## Data Mapping

| Peloton Data | FTMS Field | Notes |
|--------------|------------|-------|
| Power (W) | Instantaneous Power | Direct mapping |
| RPM | Instantaneous Cadence | Direct mapping |
| Current Resistance | Resistance Level | Normalized 0-100 |
| Calculated Speed | Instantaneous Speed | Based on power calculation |
| Accumulated Distance | Total Distance | Calculated from speed over time |
| Elapsed Time | Elapsed Time | Since service start |

## File Structure

- `ble/BleFtmsService.kt` - Main service managing the BLE server lifecycle
- `ble/BleServer.kt` - BLE GATT server implementation
- `ble/FtmsData.kt` - Data structures and byte formatting for FTMS
- `ble/FtmsUuids.kt` - FTMS service and characteristic UUIDs
- `ble/BikeDataToFtmsConverter.kt` - Conversion utilities

## Permissions Required

- `BLUETOOTH` - Basic Bluetooth access
- `BLUETOOTH_ADMIN` - Bluetooth administrative operations
- `ACCESS_FINE_LOCATION` - Required for BLE operations
- `BLUETOOTH_ADVERTISE` - Android 12+ BLE advertising permission
- `BLUETOOTH_CONNECT` - Android 12+ BLE connection permission

## Usage Instructions

1. Enable BLE FTMS in the Grupetto configuration
2. Grant Bluetooth permissions when prompted
3. Open your fitness app (Zwift, TrainerRoad, etc.)
4. Look for "Grupetto" (or your custom device name) in the app's device list
5. Connect to the device as a power meter, cadence sensor, and/or controllable trainer
6. Start your workout - the app will receive real-time data from your Peloton bike

## Troubleshooting

- **Device not appearing**: Ensure Bluetooth is enabled and permissions are granted
- **Connection issues**: Try restarting the BLE service or restarting the app
- **Data not updating**: Check that the Peloton sensor interface is working properly
- **Multiple connections**: Some apps may require exclusive access to certain characteristics

## Technical Notes

- The implementation follows the Bluetooth SIG FTMS specification
- Data is transmitted at 1-second intervals for optimal performance
- The service automatically handles multiple simultaneous connections
- Energy calculations are approximated based on power and time
- Heart rate data is not available from Peloton sensors and reports as 0
