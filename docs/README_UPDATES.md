# Suggested README.md Updates

## Add to "Features" Section

```markdown
## Features

- **Live Overlay**: System-wide overlay displaying real-time bike metrics
- **Dual Protocol Support**:
  - **BLE (Bluetooth Low Energy)**: Compatible with Zwift, TrainerRoad, Strava, and other fitness apps
  - **ANT+ (NEW!)**: Transmit to Garmin watches, Apple Watch (ANT+), cycling computers, and other ANT+ devices
- **Multi-Metric Broadcasting**: Transmits power, cadence, resistance, and speed in real-time
- **Simultaneous Dual Protocol**: Use BLE and ANT+ at the same time with the same bike data
- **Live Metrics**: Power, Cadence, Resistance, Speed with real-time updates
- **Supported Bikes**: Peloton Bike (Gen 1), Bike+ (Gen 2), and G700 CrossTrainer
```

## Add to Table of Contents

```markdown
- [Installation](#installation)
- [Usage](#usage)
- [Features](#features)
  - [BLE (Bluetooth Low Energy)](#ble-bluetooth-low-energy)
  - [ANT+ Protocol](#ant-protocol) <!-- NEW SECTION -->
- [Implementation](#implementation)
  - [Getting access to sensor data](#getting-access-to-sensor-data)
  - [Wireless Protocol Implementation](#wireless-protocol-implementation) <!-- NEW SUBSECTION -->
    - [BLE Implementation](#ble-implementation)
    - [ANT+ Implementation](#ant-implementation) <!-- NEW -->
- [Reporting Issues](#reporting-issues)
- [Unimplemented features](#unimplemented-features)
- [What's with the name?](#whats-with-the-name)
```

## Add New Sections

```markdown
## Features

### BLE (Bluetooth Low Energy)
Compatible with popular cycling and fitness apps:
- **Zwift** - Virtual cycling platform
- **TrainerRoad** - Structured workouts
- **Strava** - Activity tracking
- **Rouvy** - AR cycling
- Any app supporting **FTMS** (Fitness Machine Service) profile

**Advantages:**
- Wide app compatibility
- Better range (~100m)
- Lower power consumption

**Setup:**
1. Enable "Enable BLE TX (Transmission)" in Grupetto settings
2. In your fitness app, search for **Grupetto FTMS**
3. Connect and enjoy!

### ANT+ Protocol
**NEW!** Broadcast to ANT+ compatible sports watches, bike computers, and devices.

Compatible devices:
- Garmin sports watches (Fenix, Epix, Enduro, Forerunner series)
- Garmin cycling computers (Edge series)
- Apple Watch with ANT+ support
- Wahoo bike computers
- Any ANT+ certified device

**Advantages:**
- Lower power consumption than BLE
- Ultra-low latency
- Mesh networking capable
- Widely adopted in sports watches

**Requirements:**
- **ANT+ Radio Service** app installed from Google Play Store
- ANT+ permissions granted to Grupetto

**Setup:**
1. Install "ANT+ Radio Service" from Google Play Store
2. Enable "Enable ANT+ TX (Transmission)" in Grupetto settings
3. Grant ANT+ permissions when prompted
4. In your ANT+ device, search for **Grupetto ANT+**
5. Connect and enjoy!

**Documentation:**
- [ANT+ Quick Start Guide](ANT_PLUS_QUICKSTART.md) - User guide
- [ANT+ Integration Guide](ANT_PLUS_INTEGRATION.md) - Technical details
- [ANT+ API Reference](ANT_PLUS_API_REFERENCE.md) - Developer documentation

---

## Implementation

### Wireless Protocol Implementation

#### BLE Implementation
Grupetto broadcasts bike metrics as an **FTMS (Fitness Machine Service)** device via BLE.

**Services Implemented:**
- Fitness Machine Service (FTMS) - Primary workout metrics
- Cycling Power Service - Precise power measurements
- Cycling Speed and Cadence Service - Speed/cadence data
- Device Information Service - Device details

**Transmitted Metrics:**
- ⚡ Power (Watts)
- 🔄 Cadence (RPM)
- 📈 Speed (km/h or mph)
- 🎚️ Resistance (Peloton units)

**Configuration:**
- Customizable device name: "Grupetto FTMS" (default)
- Enabled by default for existing users

#### ANT+ Implementation
**NEW!** Grupetto broadcasts bike metrics via ANT+ protocol to compatible devices.

**ANT+ Profiles Supported:**
- Power Profile (Device ID 25) - Precise power measurements
- Speed/Cadence Profile (Device ID 121) - Speed and cadence combined

**Transmitted Metrics:**
- ⚡ Power (Watts)
- 🔄 Cadence (RPM)
- 📈 Speed (km/h)

**Configuration:**
- Customizable device name: "Grupetto ANT+" (default)
- Disabled by default (opt-in)
- Requires ANT+ Radio Service app

**File Location:**
See [ANT+ Implementation Files](ANT_PLUS_IMPLEMENTATION_SUMMARY.md) for detailed structure.

---
```

## Add to "Supported Bikes" Section

```markdown
## Supported Devices

### Bikes
- Peloton Bike (Gen 1)
- Peloton Bike+ (Gen 2)
- Peloton G700 CrossTrainer

### Protocol Compatibility

#### BLE Protocol
Compatible with any app supporting FTMS or BLE fitness services, including:
- Zwift, TrainerRoad, Rouvy, Strava, and many others

#### ANT+ Protocol (NEW!)
Compatible with ANT+ devices, including:
- Garmin sports watches (most models)
- Apple Watch with ANT+ support
- Garmin and Wahoo cycling computers
- Any ANT+ certified fitness device

---
```

## Add to Installation Section

```markdown
## Installation

### Requirements
- Android device: API 21 (Android 5.0) or higher
- For Peloton connectivity: Tablet must have access to Peloton's sensor service
- For ANT+ support: ANT+ Radio Service app must be installed

### ANT+ Setup (Optional but Recommended)

If you want to use ANT+ protocol alongside or instead of BLE:

1. **Install ANT+ Radio Service**:
   - Open Google Play Store
   - Search for "ANT+ Radio Service" by Garmin
   - Install the official app

2. **Enable in Grupetto**:
   - Open Grupetto settings
   - Toggle "Enable ANT+ TX (Transmission)"
   - Grant required permissions
   - Done! Your ANT+ devices can now connect

See [ANT+ Quick Start](ANT_PLUS_QUICKSTART.md) for more details.

---
```

## Add to Unimplemented Features Section

```markdown
## Unimplemented Features

### ANT+ Features (Planned)
- [ ] Heart rate monitoring (if available from Peloton)
- [ ] Additional ANT+ profiles (Environmental Sensor, Fitness Equipment, etc.)
- [ ] Custom ANT+ network keys for proprietary systems
- [ ] ANT+ mesh networking
- [ ] Connection diagnostics UI
- [ ] Device bonding/pairing management

### General Features
- [ ] Music integration
- [ ] Social features
- [ ] Workout history/analytics
```

## Update the "Note" Section

```markdown
***Note: This project is wholly unaffiliated with Peloton. Please do not approach them for support
with this app. It relies on undocumented interfaces that are subject to change with any update.***

**ANT+ Integration Note:** Grupetto's ANT+ implementation follows official ANT+ protocol specifications 
published by Garmin/Dynastream. ANT+ is a registered trademark of Garmin Ltd.
```

---

## Summary of Changes

Add these sections to README.md:
1. Update **Features** section to mention dual protocol support
2. Add **BLE Implementation** details
3. Add **ANT+ Implementation** details with link to documentation
4. Add **ANT+ Setup** instructions to Installation section
5. Add **ANT+ Features (Planned)** to Unimplemented Features
6. Update **Supported Devices** with ANT+ compatible devices
7. Update **Note** with ANT+ attribution

These changes will help users understand the new ANT+ capabilities and find the comprehensive documentation provided in the separate guide files.

