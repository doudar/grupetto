# AI Coding Agent Guidelines for Grupetto

## Project Overview
Grupetto is an Android application that displays live workout statistics as a system overlay on Peloton bikes and transmits data via BLE and ANT+ protocols. It supports Gen 1 Peloton Bikes, Bike+, and G700 CrossTrainer.

## Architecture
- **Core Components**: `GrupettoApplication` initializes `BleServer` and `AntPlusServer` with a shared `SensorInterface`.
- **Sensor Abstraction**: `SensorInterface` provides reactive flows for `power`, `cadence`, `resistance`, and `speed` (calculated from power).
- **Device-Specific Interfaces**: 
  - `PelotonBikeSensorInterfaceV1New` for Gen 1 bikes (connects to internal system service via binder).
  - `PelotonBikePlusSensorInterface` for Gen 2 bikes and G700.
  - `DummySensorInterface` for non-Peloton devices.
- **Transmission Protocols**: BLE (FTMS) and ANT+ (power meter) servers run independently, both consuming the same sensor data.
- **UI Framework**: Jetpack Compose with a foreground service acting as `LifecycleOwner` for overlay rendering.

## Key Patterns
- **Reactive Data Flow**: Use Kotlin Flows for sensor data and configuration state. Combine flows with `combine()` for derived states.
- **SharedPreferences Integration**: Configuration stored in SharedPreferences with `MutableStateFlow` for reactivity. Update flows in `OnSharedPreferenceChangeListener`.
- **Service Communication**: Bind to internal Peloton system services using `IBinder` for sensor access (undocumented APIs).
- **ANT+ Integration**: Local AAR dependencies (`android_antlib_4-16-0.aar`, `antpluginlib_3-9-0.aar`). Check for ANT+ Radio Service availability before initialization.
- **Logging**: Timber for debug logging, planted only in debug builds.
- **Permissions**: ANT+ requires `com.dsi.ant.permission.ANT_ADMIN` and `com.dsi.ant.permission.ANT_COMMUNICATION`.

## Development Workflow
- **Build Commands**: Use `./gradlew assembleDebug` for APK generation. Release builds require `keystore.properties`.
- **Testing**: Side-load APK via OpenPelo on Peloton tablet. Enable USB debugging and developer options first.
- **Debugging**: Overlay requires `SYSTEM_ALERT_WINDOW` permission. ANT+ needs Radio Service app installed.
- **Versioning**: Update `versionCode` and `versionName` in `app/build.gradle` for releases.

## File Structure Highlights
- `app/src/main/java/com/spop/poverlay/`: Main package with subpackages for `antplus/`, `ble/`, `sensor/`, `overlay/`, `ui/`.
- `sensor/interfaces/`: Abstractions for different bike hardware.
- `ConfigurationRepository.kt`: Centralized preferences management.
- `ANT_PLUS_INTEGRATION.md`: Detailed ANT+ setup and API usage.

## Conventions
- **Kotlin Opt-ins**: Enable experimental unsigned types, coroutines, and Compose animation APIs in `build.gradle`.
- **Compose Previews**: Use debug tooling for UI development.
- **Error Handling**: Gracefully handle missing services (e.g., emit empty flows on connection failure).
- **Resource Management**: Use `SupervisorJob` for coroutine scopes to prevent cancellation cascades.

## ANT+ Integration Breakthrough (v0.0.55+)

**CONFIRMED WORKING**: The Peloton tablet's ANT Radio Service (v41500+) can broadcast ANT+ Bike Power that Garmin Fenix 7 discovers.

### Key Technical Discovery
The Peloton's ANT Radio Service has the standard ANT+ key (0xB9:0xA5:0x21:0xFB:0xBD:0x72:0xC3:0x45) pre-loaded in the PUBLIC network slot (rawId=0), but the standard enum-based `acquireChannel()` does NOT expose this properly for Garmin discovery.

### Working Channel Acquisition Path
```kotlin
// PRIMARY: Use networkId=1 (ANT+ sport slot) via reflection bypass of SDK enum
val communicator = channelProvider.mAntChannelProvider  // reflection
val channel = communicator.acquireChannel(
    context, 
    networkId = 1,  // ANT+ network, bypasses enum limitation
    null, null, errorBundle
)
```

**Why this works**:
- networkId=1 on the Peloton service points to the ANT+ sport network (2457 MHz)
- The service auto-configures the ANT+ key for this slot
- RF frequency can be set explicitly via `channel.setRfFrequency(57)` without rejection
- Garmin watches recognize this as a valid ANT+ device

### Setup Sequence (Critical Order)
1. `channel.assign(ChannelType.BIDIRECTIONAL_MASTER)`
2. `channel.setPeriod(8182)` (standard ANT+ power meter ~4 Hz)
3. `channel.setRfFrequency(57)` (2457 MHz - Garmin discovery frequency)
4. `channel.setChannelId(deviceNumber=100, deviceType=11, txType=5)` 
5. `channel.open()`

### Discovery Mode for Testing
- `DiscoveryModeEnabled = true` forces 180W/85rpm broadcast (useful for initial pairing)
- `DiscoveryModeEnabled = false` broadcasts real sensor data from Peloton bike

### Common Issues & Resolutions
| Symptom | Cause | Fix |
|---------|-------|-----|
| Garmin can't find device | Using PUBLIC (rawId=0) instead of networkId=1 | Use reflection `acquireChannel(context, 1, ...)` |
| RF frequency INVALID_REQUEST | Setting RF on managed networks before assignment | Set RF after `assign()` and `setPeriod()` |
| 0W/0rpm on watch | Discovery mode disabled + sensor not connected | Enable discovery mode OR check sensor interface |
| Payload encoding wrong | ChannelId params swapped | Order: `ChannelId(deviceNumber, deviceType, txType)` |

### Fallback Chain
If networkId=1 fails:
1. Try PUBLIC (rawId=0) via enum - may work on different service versions
2. Try ANT_FS (rawId=2) - diagnostic only
3. acquireChannelKey() - rejected on this service build (legacy method)</content>
<parameter name="filePath">C:\Users\iulia\OneDrive\College\Documente\Development\grupetto\AGENTS.md
