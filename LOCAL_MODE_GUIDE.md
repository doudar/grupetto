# When to Use Local Mode - Quick Reference

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│                  Grupetto Local Mode Decision Tree                 │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

Question: Where is your fitness app (Zwift/MyWhoosh) running?

    ┌──────────────────────────────────────────────────────────────┐
    │                                                              │
    │  On the Peloton tablet itself                               │
    │  (sideloaded using DCRainMaker method or similar)           │
    │                                                              │
    └────────────────────┬─────────────────────────────────────────┘
                         │
                         │  ✓ ENABLE Local Mode
                         │
                         ▼
              ┌──────────────────────┐
              │                      │
              │   LOCAL MODE: ON     │
              │   ✓ Optimized for    │
              │     same device      │
              │                      │
              └──────────────────────┘


    ┌──────────────────────────────────────────────────────────────┐
    │                                                              │
    │  On an external device:                                     │
    │  - Laptop                                                   │
    │  - Phone                                                    │
    │  - Tablet                                                   │
    │  - Any device that's NOT the Peloton                       │
    │                                                              │
    └────────────────────┬─────────────────────────────────────────┘
                         │
                         │  ✓ DISABLE Local Mode
                         │
                         ▼
              ┌──────────────────────┐
              │                      │
              │   LOCAL MODE: OFF    │
              │   ✓ Optimized for    │
              │     remote devices   │
              │                      │
              └──────────────────────┘


Special Cases:

┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  🖥️  Screen Mirroring (Moonlight, Steam Link, etc.)                │
│                                                                     │
│  Fitness app runs on:  Laptop                                      │
│  Display shows on:     Peloton (mirrored)                          │
│                                                                     │
│  Use: LOCAL MODE OFF                                               │
│       (Connect from the laptop where app is actually running)      │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│  📱 Multiple Devices                                                │
│                                                                     │
│  Can't connect to both local AND remote simultaneously             │
│                                                                     │
│  Choose ONE:                                                        │
│  • Local Mode ON  → for apps on Peloton                           │
│  • Local Mode OFF → for external devices                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘


Quick Tips:

✓ Changed modes? Restart your fitness app
✓ Still not working? Toggle BLE TX off/on
✓ Need help? Check FAQ.md
✓ Technical details? See TECHNICAL_SOLUTION.md


Examples:

┌────────────────────────────────────────────────────────────────┐
│ Scenario 1: Zwift Sideloaded on Peloton                       │
├────────────────────────────────────────────────────────────────┤
│ • Zwift installed directly on Peloton tablet                  │
│ • Want to ride Zwift while on the Peloton bike                │
│ • grupetto overlay visible during ride                        │
│                                                                │
│ ✓ Enable Local Mode                                           │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│ Scenario 2: Zwift on Laptop, Watching on Peloton             │
├────────────────────────────────────────────────────────────────┤
│ • Zwift running on your laptop                                │
│ • Using Moonlight/Steam Link to view on Peloton              │
│ • grupetto transmitting from Peloton to laptop                │
│                                                                │
│ ✓ Disable Local Mode (keep it OFF)                           │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│ Scenario 3: Zwift on iPad                                     │
├────────────────────────────────────────────────────────────────┤
│ • Zwift running on iPad next to Peloton                       │
│ • Connecting to grupetto via Bluetooth                        │
│ • Watching Zwift on iPad screen or TV                         │
│                                                                │
│ ✓ Disable Local Mode (keep it OFF)                           │
└────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────┐
│ Scenario 4: Testing with Multiple Apps                        │
├────────────────────────────────────────────────────────────────┤
│ • Have both Zwift on Peloton AND laptop                       │
│ • Want to test both setups                                    │
│                                                                │
│ Switch modes as needed:                                        │
│ • ON when using Peloton Zwift                                 │
│ • OFF when using laptop Zwift                                 │
│ (restart fitness app after switching)                         │
└────────────────────────────────────────────────────────────────┘
```

## Still Confused?

If you're not sure which mode to use, follow this simple rule:

**"Is Zwift/MyWhoosh installed ON the Peloton?"**
- YES → Enable Local Mode
- NO → Disable Local Mode

If that doesn't help, check:
- [FAQ.md](FAQ.md) - Frequently asked questions
- [README.md](README.md) - Main usage guide
- GitHub Issues - Ask the community
