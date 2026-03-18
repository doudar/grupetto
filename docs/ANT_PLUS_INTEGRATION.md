# ANT+ Support Integration Guide

## Overview

ANT+ support has been successfully integrated into the Grupetto app alongside the existing BLE (Bluetooth Low Energy) implementation. This guide provides documentation on how to use and further develop the ANT+ features.

## Architecture

### ANT+ Module Structure

The ANT+ functionality is organized in a new package: `com.spop.poverlay.antplus`

**Files:**
- `AntPlusConstants.kt` - ANT+ protocol constants (device IDs, message formats, network keys)
- `AntPlusServer.kt` - Main ANT+ server managing lifecycle and sensor data broadcasting
- `AntPlusHandler.kt` - Low-level ANT+ channel communication and message formatting

### Key Design Decisions

1. **Dual Protocol Support**: Both BLE and ANT+ can be enabled independently, allowing users to choose their preferred protocol or use both simultaneously.

2. **Shared Sensor Interface**: Both BLE and ANT+ servers use the same `SensorInterface` to read bike data (power, cadence, resistance, speed), eliminating data duplication.

3. **Independent Lifecycle**: Each protocol has its own:
   - Server instance (BleServer, AntPlusServer)
   - Configuration preferences
   - Permission management
   - Enable/disable toggle in the UI

## Components Modified

### 1. Dependencies (`app/build.gradle`)
```groovy
// ANT+ support
implementation 'com.ant:antradio:3.7'
```

### 2. Permissions (`AndroidManifest.xml`)
Added ANT+ permissions:
- `android.permission.BODY_SENSORS` - For sensor access
- `com.dsi.ant.permission.ANT_ADMIN` - ANT+ administration
- `com.dsi.ant.permission.ANT_COMMUNICATION` - ANT+ communication

### 3. Configuration Repository (`ConfigurationRepository.kt`)
New preferences added:
- `AntPlusTxEnabled` - Toggle for ANT+ transmission
- `AntPlusDeviceName` - Display name for ANT+ device

Public properties:
- `antPlusTxEnabled: Flow<Boolean>`
- `antPlusDeviceName: Flow<String>`

Public methods:
- `setAntPlusTxEnabled(enabled: Boolean)`
- `setAntPlusDeviceName(name: String)`

### 4. Application Class (`GrupettoApplication.kt`)
```kotlin
lateinit var antPlusServer: AntPlusServer
    private set

override fun onCreate() {
    // ... existing code ...
    antPlusServer = AntPlusServer(this, sensorInterface)
}
```

### 5. ViewModel (`ConfigurationViewModel.kt`)
New properties:
- `antPlusTxEnabled` - StateFlow for ANT+ transmission state
- `antPlusDeviceName` - StateFlow for ANT+ device name

New methods:
- `onAntPlusTxEnabledClicked(isChecked: Boolean)` - Handler for ANT+ toggle
- `onAntPlusDeviceNameChanged(newName: String)` - Handler for device name changes
- `hasAntPlusPermissions(): Boolean` - Permission checker
- `getRequiredAntPlusPermissions(): Array<String>` - Determine required permissions

### 6. UI (`ConfigurationPage.kt`)
Added ANT+ configuration section with:
- Enable/Disable checkbox for ANT+ TX
- Device name display
- ANT+ availability status
- Help text about ANT+ Radio Service requirement

## Usage

### For Users

1. **Install ANT+ Radio Service**: Required app that must be installed from Google Play Store
   - The app will automatically detect if it's installed and disable ANT+ if unavailable

2. **Enable ANT+ in Grupetto**:
   - Open Grupetto settings
   - Toggle "Enable ANT+ TX (Transmission)?"
   - Grant ANT+ permissions when prompted
   - ANT+ will begin broadcasting power and cadence data

3. **Connect ANT+ Devices**:
   - Your ANT+ compatible sports watch, bike computer, or app can now connect
   - Look for device named "Grupetto ANT+" (customizable in settings)

### For Developers

#### Initializing ANT+ Server

```kotlin
val sensorInterface = createSensorInterface()
val antPlusServer = AntPlusServer(context, sensorInterface, "Grupetto ANT+")

// Check if ANT+ is available
if (antPlusServer.isAntPlusAvailable()) {
    antPlusServer.start()
}
```

#### Adding New ANT+ Profile Support

To support additional ANT+ profiles (e.g., different sensors):

1. Create a new profile handler in the `antplus` package:
```kotlin
class AntPlusHeartRateProfile(handler: AntPlusHandler) {
    fun broadcastHeartRate(bpm: Int) {
        // Format heartrate according to ANT+ spec
    }
}
```

2. Register the profile in `AntPlusHandler.initialize()`

#### ANT+ Message Format Reference

**Power Meter (Page 16 - Standard Power Only):**
```
Byte 0:   Page number (0x10)
Bytes 1-2: Pedal power (unused)
Bytes 3-4: Instantaneous power (watts, little-endian)
Bytes 5-6: Accumulated power (little-endian)
Byte 7:   Timestamp
```

**Speed & Cadence (Page 1):**
```
Byte 0:   Page number (0x01)
Bytes 1-2: Cadence (RPM)
Bytes 3-4: Speed (sensor-dependent)
Bytes 5-7: Timestamp
```

## Prerequisites & Dependencies

### Required on Device
- **ANT+ Radio Service** - Must be installed from Google Play Store
  - Package: `com.dsi.ant.service.socket`
  - App will gracefully handle if not installed

### Required Permissions (Android)
- `BODY_SENSORS` (Android 10+)
- `com.dsi.ant.permission.ANT_COMMUNICATION`
- `com.dsi.ant.permission.ANT_ADMIN`

### Gradle Dependency
```groovy
implementation 'com.ant:antradio:3.7'
```

## Testing

### Unit Tests
Currently no unit tests exist. Recommended to add:
- Mock `AntChannelProvider` for offline testing
- Message format validation tests
- Permission checking logic

### Integration Testing
1. **On Android Device with ANT+ Radio Service**:
   - Install the app via Android Studio
   - Grant all required permissions
   - Enable ANT+ in settings
   - Verify broadcast with ANT+ capable watch or app

2. **Without ANT+ Radio Service**:
   - App should gracefully disable ANT+ option
   - No crashes or errors

3. **Permission Denied Scenarios**:
   - Deny ANT+ permissions - should show permission dialog
   - Revoke permissions from settings - should disable ANT+ on app resume

## Known Limitations & Future Work

### Current Limitations
1. **Single Channel**: Currently uses one power channel and one speed/cadence channel
   - Could expand to support multiple device profiles
2. **No Mesh Networking**: ANT+ mesh features not implemented
3. **Fixed Network Key**: Uses public ANT+ network key only
   - Could be configurable for closed networks

### Recommended Future Enhancements
1. Add support for additional ANT+ profiles:
   - Heart Rate Monitor (Profile ID 78)
   - Environment Sensor (Profile ID 25)
   - Fitness Equipment (Profile ID 17)

2. Advanced features:
   - ANT+ channel monitoring and diagnostics
   - Connection state UI indicators
   - History/logging of connected devices

3. User experience:
   - Customizable ANT+ device names per profile
   - Pairing/bonding UI
   - ANT+ connection status in overlay

## Troubleshooting

### ANT+ TX Not Working

**Problem**: ANT+ toggle appears disabled or grayed out
- **Solution**: Install ANT+ Radio Service from Google Play Store

**Problem**: "ANT+ Radio Service not found" message
- **Solution**: Open Google Play Store and install "ANT+ Radio Service" app

**Problem**: ANT+ devices not connecting
- **Checklist**:
  1. ANT+ TX is enabled in Grupetto settings
  2. ANT+ Radio Service is running (check Android Services)
  3. Device is searching for ANT+ sensors (not paired devices)
  4. Try restarting both apps

### Permissions Issues

**Problem**: Permission denied error
- **Solution**: 
  1. Open Android Settings → Apps → Grupetto
  2. Go to Permissions
  3. Grant "Sensors" (BODY_SENSORS) permission
  4. Grant "ANT Communication" and "ANT Admin" permissions if available

## Security Considerations

1. **Network Key**: Using public ANT+ network key - suitable for sports/fitness use
   - For proprietary use, a custom network key would be needed

2. **Data Privacy**: Broadcasts power/cadence data wirelessly
   - Only ANT+ paired devices within ~10m range can receive
   - No sensitive personal data transmitted

3. **Permissions**: Requires `BODY_SENSORS` permission
   - Users grant explicit permission in Android 10+
   - Shown in Android System Privacy Dashboard

## References

- [ANT+ Official Protocol Documentation](https://www.thisisant.com/)
- [ANT+ Device Profiles](https://www.thisisant.com/developer/ant-plus/device-profiles/)
- [Android ANT Radio SDK](https://www.thisisant.com/developer/ant-plus/ant-android-sdk/)

## Migration Guide

If upgrading from a BLE-only version:

1. **Automatic**: ANT+ is disabled by default
   - Existing users unaffected
   - No breaking changes to BLE functionality

2. **Manual**: Users can opt-in by:
   - Updating the app
   - Opening settings
   - Enabling "Enable ANT+ TX (Transmission)?"
   - Granting required permissions

## Support

For issues or questions about ANT+ integration:
1. Check the troubleshooting section above
2. Review the logs using Android Studio's Logcat (filter: tag=Timber)
3. Ensure ANT+ Radio Service is installed and up-to-date
4. Test BLE functionality to confirm sensor data is being read correctly

