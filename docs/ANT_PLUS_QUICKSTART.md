# ANT+ Quick Start Guide

## What's New?

Grupetto now supports ANT+ protocol alongside BLE (Bluetooth Low Energy) for transmitting cycling power and cadence metrics.

## Installation Steps

### Step 1: Install ANT+ Radio Service
**This is required for ANT+ to work!**

1. Open Google Play Store on your Android device
2. Search for **"ANT+ Radio Service"**
3. Install the official app from **Garmin Connect IQ**

### Step 2: Update Grupetto
1. Install the latest version of Grupetto that includes ANT+ support
2. Grant all required permissions when prompted

### Step 3: Enable ANT+ in Grupetto

1. Open Grupetto app
2. Look for **"Enable ANT+ TX (Transmission)?"** toggle
3. Toggle it ON
4. When prompted, grant these permissions:
   - ✅ Body Sensors (Android 10+)
   - ✅ ANT Communication
   - ✅ ANT Admin

### Step 4: Verify ANT+ is Broadcasting

- You should see: **"✅ ANT+ TX is enabled"** with device name **"Grupetto ANT+"**
- Open your ANT+ sports watch or bike computer
- Search for ANT+ sensors
- Look for device named **"Grupetto ANT+"**
- Connect to it

## Troubleshooting

### "ANT+ Radio Service not installed"
**Solution**: Go to Google Play Store and install "ANT+ Radio Service" app

### ANT+ toggle is disabled/grayed out
**Solution**: Install ANT+ Radio Service from Google Play Store first

### ANT+ devices not connecting
1. Make sure ANT+ TX is enabled in Grupetto
2. Restart the ANT+ Radio Service app
3. Make sure your sports watch/bike computer is in pairing mode
4. Restart Grupetto app

### I don't see the ANT+ option in Grupetto
**Solution**: 
- Update to the latest version of Grupetto
- Clear app cache: Settings → Apps → Grupetto → Storage → Clear Cache
- Restart the app

## Comparing BLE vs ANT+

| Feature | BLE | ANT+ |
|---------|-----|------|
| Range | ~100m | ~10m |
| Power | Low | Ultra-low |
| Apps | Zwift, TrainerRoad, etc. | Sports watches, Garmin devices |
| Setup | Simple | Requires ANT+ Radio Service |
| Simultaneous | Yes | Yes |
| Protocols | Can use both at once | Can use both at once |

## Using Both BLE and ANT+ Together

You can enable **both** BLE and ANT+ simultaneously!

1. Enable **"Enable BLE TX (Transmission)?"** ✅
2. Enable **"Enable ANT+ TX (Transmission)?"** ✅
3. Grupetto will now broadcast to both:
   - BLE: Zwift, Strava, TrainerRoad, etc.
   - ANT+: Garmin watches, Apple Watch (ANT+), cycling computers

## Device Names

Default broadcast names:
- **BLE Device**: `Grupetto FTMS`
- **ANT+ Device**: `Grupetto ANT+`

Both transmit the same data:
- ⚡ Power (Watts)
- 🔄 Cadence (RPM)
- 🚴 Resistance
- 📈 Speed

## Supported Devices

### ANT+ Compatible Receivers
- Garmin sports watches (Fenix, Epix, Enduro series)
- Garmin cycling computers (Edge series)
- Apple Watch with ANT+ support
- Wahoo bike computers
- Any ANT+ certified device with power meter profile support

### Not Supported Yet
- Heart rate monitoring (Grupetto doesn't read HR from Peloton)
- Gradient/incline data (Peloton resistance only)
- Cadence-only profiles

## Questions & Support

For detailed technical information, see:
- **ANT_PLUS_INTEGRATION.md** - Full technical guide
- **ANT_PLUS_IMPLEMENTATION_SUMMARY.md** - What was changed

## Next Steps

1. ✅ Install ANT+ Radio Service
2. ✅ Enable ANT+ in Grupetto
3. ✅ Pair your ANT+ devices
4. ✅ Enjoy your workouts with data on multiple devices!

---

**Note**: Grupetto is not affiliated with or endorsed by Peloton. ANT+ is a technology by Garmin/Dynastream.

