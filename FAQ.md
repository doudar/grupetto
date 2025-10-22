# Frequently Asked Questions - Local Mode

## What is Local Mode?

Local Mode is a feature that optimizes grupetto's Bluetooth broadcasting for apps running on the same Peloton device. When enabled, it makes it easier for locally installed apps like Zwift or MyWhoosh to discover and connect to grupetto's virtual power meter and cadence sensor.

## When should I use Local Mode?

**Enable Local Mode when:**
- You have Zwift, MyWhoosh, or another fitness app installed directly on your Peloton tablet
- You want to use these apps with grupetto's sensor data
- You're riding directly on the Peloton without streaming to another device

**Disable Local Mode when:**
- You're connecting to grupetto from a laptop, phone, or tablet
- You're using screen mirroring/casting to watch Zwift on the Peloton
- You want maximum range and performance for remote Bluetooth connections

## Can I use both local and remote connections at the same time?

Generally, no. You should choose one mode:
- **Local Mode ON**: Best for apps on the Peloton itself
- **Local Mode OFF**: Best for external devices

Some advanced setups might support multiple simultaneous connections, but in most cases, you'll want to pick the mode that matches your primary use case.

## I enabled Local Mode but Zwift/MyWhoosh still can't find the sensor. What should I do?

Try these troubleshooting steps in order:

1. **Restart the fitness app**: Force close Zwift/MyWhoosh and reopen it

2. **Toggle BLE TX**: 
   - Open grupetto
   - Uncheck "Enable BLE TX"
   - Wait 5 seconds
   - Check "Enable BLE TX" again
   - Start the overlay

3. **Restart the overlay**:
   - Stop the grupetto overlay
   - Return to the main screen
   - Start the overlay again

4. **Check Bluetooth permissions**:
   - Make sure Zwift/MyWhoosh has Bluetooth permissions
   - Go to Android Settings > Apps > [Your App] > Permissions
   - Enable Location and Bluetooth permissions

5. **Restart the Peloton tablet**:
   - As a last resort, reboot the Peloton
   - Launch grupetto and enable Local Mode
   - Then launch your fitness app

## What's the difference between Local Mode and regular mode technically?

Local Mode changes how grupetto advertises itself over Bluetooth:

**Regular Mode (Remote):**
- Optimized for maximum range
- Fast updates for external devices  
- Multiple service UUIDs advertised

**Local Mode:**
- Optimized for same-device discovery
- More balanced advertising timing
- Device name prominently featured
- Simplified service advertisement

Both modes provide the same sensor data once connected.

## Does Local Mode affect the overlay or sensor accuracy?

No! Local Mode only changes how grupetto advertises itself via Bluetooth. The sensor data, overlay functionality, and accuracy remain exactly the same.

## I'm using screen mirroring to watch Zwift on my Peloton. Which mode should I use?

If you're using **screen mirroring** (like Moonlight, Steam Link, or similar), you should:
- **Disable Local Mode** (keep it OFF)
- Run Zwift on your laptop/PC
- Connect Zwift to grupetto from the laptop
- Mirror/cast the laptop screen to the Peloton

In this scenario, Zwift is running on the laptop, not locally on the Peloton, so you want Remote Mode.

## Can I leave Local Mode enabled all the time?

You can, but it's not recommended. Local Mode is optimized for same-device connections and may not provide the best experience for remote connections. It's better to:
- Enable Local Mode when using local apps
- Disable Local Mode when connecting from external devices

## Does Local Mode work with other apps besides Zwift and MyWhoosh?

Yes! Local Mode should work with any app that:
- Scans for Bluetooth LE fitness sensors
- Supports Cycling Power Service (standard power meters)
- Supports Cycling Speed and Cadence Service
- Supports Fitness Machine Service (FTMS)

Examples include TrainerRoad, Rouvy, Sufferfest, and many others.

## I switched modes but don't see any difference. Is it working?

The difference is primarily in HOW grupetto advertises, not in the user interface. To verify Local Mode is working:

1. Check the grupetto logs (if you have USB debugging enabled)
2. Look for "BLE advertising started successfully in LOCAL mode" or "REMOTE mode"
3. Try connecting from your fitness app - it should be more reliable in the correct mode

## What if my Peloton doesn't support Bluetooth LE?

All Generation 2 Peloton bikes (the ones with the Android tablet) support Bluetooth LE. If you have an original Peloton bike with the older tablet, grupetto may not work. This feature is designed for Gen 2 bikes.

## Does enabling Local Mode drain the battery faster?

No. Both Local Mode and Remote Mode use approximately the same amount of power. Bluetooth LE is designed to be energy-efficient, and the mode primarily affects advertising parameters, not power consumption.

## Can I use Local Mode with ANT+ devices?

Grupetto currently only supports Bluetooth LE, not ANT+. Local Mode is a Bluetooth-specific feature. If you need ANT+ support, you would need a separate ANT+ adapter and different software.

## I'm a developer. Can I integrate my app with grupetto?

Yes! Check out the [LOCAL_APP_INTEGRATION.md](LOCAL_APP_INTEGRATION.md) guide for detailed information about:
- Discovering grupetto's BLE services
- Connecting to the GATT server
- Reading sensor data
- Subscribing to notifications
- Code examples and best practices

## Will Local Mode work on future Peloton updates?

Grupetto relies on undocumented Peloton interfaces, so any Peloton software update could potentially break it. However, Local Mode is built on standard Android Bluetooth APIs, so it should be more resilient to updates than other parts of grupetto.

If an update breaks something, check for new releases of grupetto or report the issue on GitHub.

## Can I contribute improvements to Local Mode?

Absolutely! Grupetto is open source. If you have ideas for improving Local Mode or find bugs, please:
1. Open an issue on the GitHub repository
2. Submit a pull request with your improvements
3. Share your experiences in the discussions

## Is there a performance difference between modes?

The performance of the sensor data and overlay is identical in both modes. The only difference is in Bluetooth discoverability:
- Local Mode may be slightly better at maintaining connections with local apps
- Remote Mode may provide better range for distant devices

For most users, the difference is negligible once connected.

## Technical question: What specific Bluetooth parameters change in Local Mode?

For technical users, here are the key changes:

**Remote Mode:**
- Advertise Mode: `LOW_LATENCY`
- Device Name: In scan response
- Service UUIDs: All services in primary advertisement

**Local Mode:**
- Advertise Mode: `BALANCED`
- Device Name: In primary advertisement (higher priority)
- Service UUIDs: Cycling Power Service (0x1818) prioritized
- Manufacturer Data: Includes "-LOCAL" suffix

See [TECHNICAL_SOLUTION.md](TECHNICAL_SOLUTION.md) for complete technical details.

## Who do I contact for support?

For issues with grupetto:
- Open an issue on the [GitHub repository](https://github.com/doudar/grupetto)
- Include details about your Peloton model, Android version, and the fitness app you're using
- Include logs if possible (enable USB debugging)

**Important**: Do NOT contact Peloton support about grupetto. They do not support third-party apps and are not involved with this project.
