# ANT+ API Reference for Developers

## AntPlusServer API

Main entry point for ANT+ functionality.

### Constructor
```kotlin
AntPlusServer(
    context: Context,
    sensorInterface: SensorInterface,
    deviceName: String = "Grupetto ANT+"
)
```

**Parameters:**
- `context` - Android application context
- `sensorInterface` - Interface providing power/cadence/speed sensor data
- `deviceName` - Display name for ANT+ device (max 32 chars recommended)

### Public Methods

#### `isAntPlusAvailable(): Boolean`
Checks if ANT+ Radio Service is installed on the device.

**Returns:** `true` if ANT+ Radio Service app is installed, `false` otherwise

**Usage:**
```kotlin
if (antPlusServer.isAntPlusAvailable()) {
    antPlusServer.start()
} else {
    showMessage("Please install ANT+ Radio Service from Google Play")
}
```

#### `start(): Unit`
Starts ANT+ server and begins broadcasting sensor data.

**Behavior:**
- Checks ANT+ Radio Service availability
- Validates required permissions
- Initializes ANT+ channels
- Subscribes to sensor data flows
- Logs warnings if conditions not met

**Exceptions:** None (handles errors gracefully)

**Usage:**
```kotlin
antPlusServer.start()
```

#### `stop(): Unit`
Stops ANT+ server and closes all channels.

**Behavior:**
- Cancels sensor data collection
- Closes ANT+ channels
- Cleans up resources
- Thread-safe (can be called multiple times)

**Usage:**
```kotlin
antPlusServer.stop()
```

#### `hasConnectedDevices(): Boolean`
Checks if any ANT+ devices are currently connected.

**Returns:** `true` if at least one ANT+ channel is active

**Usage:**
```kotlin
if (antPlusServer.hasConnectedDevices()) {
    updateUIWithConnectionStatus("Connected")
}
```

#### `updateDeviceName(newName: String): Unit`
Updates the ANT+ device name (requires restart).

**Note:** Changes take effect only on next `start()` call

**Usage:**
```kotlin
antPlusServer.updateDeviceName("My Peloton")
antPlusServer.stop()
antPlusServer.start()
```

---

## AntPlusHandler API

Low-level ANT+ channel management.

**Note:** This is an internal API. Use `AntPlusServer` for public access.

### Constructor
```kotlin
AntPlusHandler(
    context: Context,
    deviceName: String
)
```

### Public Methods

#### `suspend fun initialize(): Unit`
Initializes ANT+ channels and connects to ANT+ Radio Service.

**Behavior:**
- Obtains ANT+ channel provider
- Creates power meter channel
- Creates speed/cadence channel
- Configures and opens both channels

**Exceptions:** Throws on initialization failure

**Usage:**
```kotlin
launch(Dispatchers.IO) {
    try {
        handler.initialize()
    } catch (e: Exception) {
        Timber.e(e, "Failed to initialize ANT+")
    }
}
```

#### `fun broadcastPowerData(powerWatts: Int): Unit`
Broadcasts power data via ANT+ power meter channel.

**Parameters:**
- `powerWatts` - Power in watts (0-4095)

**Message Format:** ANT+ Power Meter Page 16
```
Byte 0:     0x10 (page number)
Bytes 1-2:  0x00 (reserved)
Bytes 3-4:  Power (little-endian, watts)
Bytes 5-6:  Accumulated counter
Byte 7:     Timestamp
```

**Usage:**
```kotlin
handler.broadcastPowerData(150) // 150 watts
```

#### `fun broadcastSpeedCadenceData(cadenceRpm: Int, speedKmh: Float): Unit`
Broadcasts speed and cadence data via ANT+ sensor channel.

**Parameters:**
- `cadenceRpm` - Cadence in revolutions per minute (0-255)
- `speedKmh` - Speed in kilometers per hour

**Message Format:** ANT+ Speed & Cadence Page 1
```
Byte 0:     0x01 (page number)
Bytes 1-2:  Cadence (little-endian, RPM)
Bytes 3-4:  Speed (sensor-dependent units)
Bytes 5-7:  Timestamp + counter
```

**Usage:**
```kotlin
handler.broadcastSpeedCadenceData(90, 35.5f) // 90 RPM, 35.5 km/h
```

#### `fun hasConnectedDevices(): Boolean`
Checks if ANT+ channels are open/active.

**Returns:** `true` if any channel is open

**Usage:**
```kotlin
if (handler.hasConnectedDevices()) {
    Timber.d("ANT+ devices connected")
}
```

#### `suspend fun shutdown(): Unit`
Closes all ANT+ channels and cleans up resources.

**Behavior:**
- Closes power meter channel
- Closes speed/cadence channel
- Releases channel provider

**Usage:**
```kotlin
launch(Dispatchers.IO) {
    handler.shutdown()
}
```

---

## AntPlusConstants Reference

### Device Type IDs
```kotlin
const val DEVICE_TYPE_POWER_METER = 25.toByte()      // Power meter profile
const val DEVICE_TYPE_SPEED_CADENCE = 121.toByte()   // Speed/Cadence sensor
```

### Message IDs
```kotlin
const val MESG_BROADCAST_DATA_ID = 0x4E      // Standard broadcast message
const val MESG_ACKNOWLEDGED_DATA_ID = 0x4F   // Acknowledged message
const val MESG_BURST_DATA_ID = 0x50          // Burst message
```

### Page Numbers
```kotlin
const val POWER_METER_PAGE_STANDARD = 16     // Standard power-only data
const val SPEED_CADENCE_PAGE_DATA = 1        // Combined speed & cadence
```

### Network Key
```kotlin
val PUBLIC_NETWORK_KEY = byteArrayOf(
    0xE8.toByte(), 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09
)
```

---

## ConfigurationRepository ANT+ Methods

### Properties
```kotlin
val antPlusTxEnabled: Flow<Boolean>      // ANT+ transmission enabled state
val antPlusDeviceName: Flow<String>      // ANT+ device display name
```

### Methods

#### `fun setAntPlusTxEnabled(enabled: Boolean): Unit`
Enable/disable ANT+ transmission and persist preference.

**Usage:**
```kotlin
configRepo.setAntPlusTxEnabled(true)
```

#### `fun setAntPlusDeviceName(name: String): Unit`
Update ANT+ device name and persist preference.

**Usage:**
```kotlin
configRepo.setAntPlusDeviceName("My Peloton")
```

---

## ConfigurationViewModel ANT+ Methods

### Properties
```kotlin
val antPlusTxEnabled: Flow<Boolean>      // Reactive ANT+ enabled state
val antPlusDeviceName: Flow<String>      // Reactive device name
```

### Methods

#### `fun onAntPlusTxEnabledClicked(isChecked: Boolean): Unit`
Handle ANT+ toggle from UI.

**Behavior:**
- Updates configuration
- Checks ANT+ availability
- Requests permissions if needed
- Starts/stops ANT+ server
- Shows user messages

**Usage:**
```kotlin
viewModel.onAntPlusTxEnabledClicked(true)
```

#### `fun onAntPlusDeviceNameChanged(newName: String): Unit`
Handle device name change from UI.

**Usage:**
```kotlin
viewModel.onAntPlusDeviceNameChanged("My ANT+ Device")
```

#### `fun hasAntPlusPermissions(): Boolean`
Check if all required ANT+ permissions are granted.

**Returns:** `true` if all permissions granted

**Usage:**
```kotlin
if (viewModel.hasAntPlusPermissions()) {
    antPlusServer.start()
}
```

#### `fun getRequiredAntPlusPermissions(): Array<String>`
Get list of required ANT+ permissions.

**Returns:** Array of permission strings to request

**Usage:**
```kotlin
val permissions = viewModel.getRequiredAntPlusPermissions()
requestPermissions(permissions, REQUEST_CODE)
```

---

## Sensor Interface Integration

### SensorInterface
ANT+ reads data from the shared sensor interface:

```kotlin
interface SensorInterface {
    val power: Flow<Float>        // Power in watts
    val cadence: Flow<Float>      // Cadence in RPM
    val resistance: Flow<Float>   // Resistance (Peloton units)
    val speed: Flow<Float>        // Speed (calculated from power)
}
```

### Usage in AntPlusServer
```kotlin
// Subscribe to power updates
sensorInterface.power.collect { power ->
    antPlusHandler?.broadcastPowerData(power.toInt())
}
```

---

## Best Practices

### 1. Permission Checking
```kotlin
// Always check permissions before starting
if (antPlusServer.isAntPlusAvailable() && hasAntPlusPermissions()) {
    antPlusServer.start()
}
```

### 2. Lifecycle Management
```kotlin
// In Activity/Fragment
override fun onPause() {
    super.onPause()
    antPlusServer.stop()  // Stop to save battery
}

override fun onResume() {
    super.onResume()
    if (antPlusTxEnabled) {
        antPlusServer.start()
    }
}
```

### 3. Error Handling
```kotlin
try {
    antPlusServer.start()
} catch (e: Exception) {
    Timber.e(e, "Failed to start ANT+")
    showErrorDialog("ANT+ initialization failed")
}
```

### 4. Testing
```kotlin
// Test ANT+ availability without starting
val isAvailable = antPlusServer.isAntPlusAvailable()
if (!isAvailable) {
    showInstallPrompt("ANT+ Radio Service")
    return
}

// Safe to start now
antPlusServer.start()
```

---

## Logging & Debugging

Enable debug logging with Timber:

```kotlin
Timber.d("ANT+ server state: %s", if (isRunning) "running" else "stopped")
Timber.d("ANT+ devices connected: %s", hasConnectedDevices())
Timber.w("ANT+ Radio Service not found")
Timber.e(exception, "ANT+ initialization failed")
```

Filter logcat:
```bash
adb logcat | grep "ANT\|poverlay"
```

---

## Version Compatibility

- **Minimum SDK**: API 21 (Android 5.0)
- **Target SDK**: API 34 (Android 14)
- **Gradle**: 7.0+
- **Kotlin**: 1.9.0+
- **ANT SDK**: 3.7

---

## Known Limitations

1. **Single device name** - Same name for all ANT+ profiles
2. **No authentication** - Uses public ANT+ network key only
3. **Limited profiles** - Currently supports power and speed/cadence only
4. **No mesh networking** - Standard ANT+ broadcasting only

See **ANT_PLUS_INTEGRATION.md** for future enhancement ideas.

