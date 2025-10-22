# Technical Solution: Local Bluetooth Connectivity

## Problem Statement

Users reported that when running fitness apps like Zwift or MyWhoosh **locally on the Peloton device** (using sideloading methods), these apps could not detect grupetto's Bluetooth LE (BLE) power meter and cadence sensor. This worked fine when connecting from **external devices** like laptops or phones, but failed for same-device connections.

## Root Cause Analysis

The issue stems from how Android's Bluetooth LE stack handles simultaneous advertising and scanning on the same device:

1. **BLE Advertising vs. Scanning**: When grupetto acts as a BLE peripheral (GATT server), it advertises its services. Local apps on the same device act as BLE centrals (GATT clients) and need to scan for devices.

2. **Radio Limitations**: While Android technically supports a device acting as both peripheral and central simultaneously, there are practical limitations:
   - The Bluetooth radio may struggle to efficiently advertise while scanning locally
   - Advertising parameters optimized for remote connections may not work well for local discovery
   - Some Android devices have firmware that doesn't handle this scenario well

3. **Advertising Configuration**: The default advertising configuration was optimized for **remote connections** (laptops, phones), using:
   - `ADVERTISE_MODE_LOW_LATENCY` - prioritizes fast updates for remote devices
   - Service UUIDs in primary advertisement - optimal for remote scanning
   - High TX power - good for range but not necessary for local connections

## Solution: Local Mode

We implemented a **Local Mode** feature that optimizes BLE advertising specifically for same-device connections:

### 1. Configuration Option (`BleLocalMode`)
- Added a user-facing toggle in the app settings
- Persisted in SharedPreferences
- Enables users to switch between Remote Mode (default) and Local Mode

### 2. Optimized Advertising Parameters

When Local Mode is enabled, the advertising configuration changes:

```kotlin
// Remote Mode (default)
- ADVERTISE_MODE_LOW_LATENCY  // Fast updates, optimized for range
- All service UUIDs in advertisement
- Device name in scan response

// Local Mode  
- ADVERTISE_MODE_BALANCED     // Better compatibility for local scanning
- Device name in primary advertisement (higher priority)
- Only Cycling Power UUID in primary (stays under size limit)
- "-LOCAL" suffix in manufacturer data for identification
```

**Why these changes help:**

1. **BALANCED Mode**: Uses a more conservative advertising interval that's more compatible with simultaneous local scanning. Low latency mode can cause timing issues when scanning locally.

2. **Device Name Priority**: Local scanning apps often filter by device name first. Putting the device name in the primary advertisement (not just scan response) makes discovery more reliable.

3. **Simplified Service List**: The 31-byte advertisement size limit is strict. In Local Mode, we prioritize the Cycling Power Service UUID since that's what most fitness apps look for first.

### 3. Dynamic Mode Switching

The `setLocalMode()` method allows switching modes without restarting the app:

```kotlin
fun setLocalMode(enabled: Boolean) {
    if (localMode != enabled) {
        localMode = enabled
        if (advertiser != null) {
            stopAdvertising()
            startAdvertising()  // Restart with new parameters
        }
    }
}
```

### 4. Enhanced Logging

Added detailed logging to help diagnose connection issues:
- Logs when devices connect/disconnect with device name and address
- Indicates current mode (LOCAL/REMOTE) in connection logs
- Detailed error messages for advertising failures
- Helps users troubleshoot connection problems

## Why This Works

The solution addresses the core issue in multiple ways:

1. **Improved Discoverability**: Local Mode advertising parameters are specifically tuned for apps scanning on the same device. The balanced advertising mode and device name prioritization make the device more "visible" to local scanners.

2. **Standards Compliant**: We still follow Bluetooth specifications for Cycling Power, CSC, and Fitness Machine services. The changes are only in HOW we advertise, not WHAT we advertise.

3. **User Control**: Users can easily switch modes based on their use case:
   - Local Mode: For Zwift/MyWhoosh on the Peloton
   - Remote Mode: For connecting from laptops, phones, tablets

4. **Backwards Compatible**: Existing users connecting from external devices are unaffected. Local Mode is opt-in.

## Technical Alternatives Considered

### 1. Bound Service Approach
**Idea**: Create an Android Service that local apps could bind to directly, bypassing BLE.

**Why not**: 
- Zwift, MyWhoosh, and similar apps are designed to discover BLE devices
- They don't have custom code to bind to grupetto's services
- Would require modifying third-party apps (not feasible)

### 2. Intent Broadcast System
**Idea**: Broadcast sensor data via Android Intents that local apps could receive.

**Why not**:
- Same issue as Bound Service - fitness apps expect BLE, not Intents
- Would require custom integration in each fitness app
- Not a standard approach for fitness sensor data

### 3. BLE Relay/Bridge
**Idea**: Create a companion app that connects to grupetto's BLE server and re-broadcasts with different parameters.

**Why not**:
- Adds complexity and another app to manage
- Still needs to solve the local connection problem
- The direct approach (Local Mode) is simpler and more efficient

### 4. Disable Scanning While Advertising
**Idea**: Temporarily stop advertising to allow local apps to scan, then resume.

**Why not**:
- Would interrupt remote connections
- Complex state management
- Still doesn't solve the discoverability issue

## Implementation Details

### Files Modified

1. **ConfigurationRepository.kt**
   - Added `BleLocalMode` preference
   - Added getter/setter for local mode state

2. **ConfigurationViewModel.kt**
   - Added `bleLocalMode` property
   - Added `onBleLocalModeClicked()` callback
   - Initializes BleServer with correct mode on startup

3. **BleServer.kt**
   - Added `localMode` parameter to constructor
   - Implemented `setLocalMode()` for dynamic switching
   - Modified `startAdvertising()` to use different parameters based on mode
   - Enhanced logging for diagnostics

4. **GrupettoApplication.kt**
   - Added `updateBleLocalMode()` method
   - Wires configuration changes to BleServer

5. **ConfigurationPage.kt**
   - Added UI toggle for Local Mode
   - Added explanatory text for when to use each mode
   - Color-coded feedback (green for enabled, gray for disabled)

6. **README.md**
   - Added comprehensive "Using Grupetto with Local Apps" section
   - Troubleshooting steps
   - Explanation of when to use each mode

7. **LOCAL_APP_INTEGRATION.md** (new file)
   - Detailed developer guide for integrating with grupetto
   - Code examples for discovering and connecting
   - Bluetooth specification references
   - Best practices for BLE integration

## Testing Recommendations

To verify the solution works:

1. **Local Mode ON**:
   - Install Zwift/MyWhoosh on the Peloton
   - Enable Local Mode in grupetto
   - Start the overlay
   - Open fitness app and search for sensors
   - Verify "Grupetto FTMS" appears in the sensor list
   - Connect and verify data flows correctly

2. **Local Mode OFF**:
   - Disable Local Mode
   - Connect from a laptop/phone running Zwift
   - Verify the connection works normally
   - Ensure no regression in remote connectivity

3. **Mode Switching**:
   - Toggle Local Mode while BLE is running
   - Verify advertising restarts with new parameters
   - Check logs confirm mode change

4. **Multiple Scenarios**:
   - Test with different Android versions
   - Test with different Peloton firmware versions
   - Test with various fitness apps

## Future Enhancements

Potential improvements for future versions:

1. **Auto-Detection**: Automatically detect if fitness apps are running locally and suggest enabling Local Mode

2. **Smart Mode**: Automatically switch between Local and Remote based on connection patterns

3. **Per-App Configuration**: Remember mode preference for specific apps

4. **Enhanced Diagnostics**: Add a "Test BLE" button that validates the advertising is working correctly

5. **Connection Manager**: Show a list of connected devices in the UI with ability to disconnect specific devices

## Conclusion

The Local Mode feature provides a practical, standards-compliant solution to enable local app connectivity while maintaining excellent support for remote connections. The implementation is minimal, well-documented, and provides users with clear guidance on when and how to use each mode.
