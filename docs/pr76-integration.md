# PR #76 integration

Adds optional ANT+ bike power, combined speed/cadence, and heart-rate transmission;
opt-in overlay startup after boot; heart-rate zone colors; repeated low-power
heart-rate reconnection; and shared sensor/service lifecycle improvements.

The existing platform-based Bike/Tread selection is retained. ANT+ cycling
settings and transmission are disabled on a detected Tread. Tread BLE telemetry,
bike resistance handling, session peak tracking, and BLE descriptor reads remain
available. BLE permission denial still disables BLE independently of ANT+.

## Corrections made while integrating

- ANT+ combined speed/cadence uses four little-endian 16-bit fields, with no page
  byte. The crank counter must remain 16 bits, including rollover.
- A disconnected heart-rate source clears the ANT+ BPM instead of retaining the
  previous reading. Stop cancels the session and releases its handler immediately.
- Binding cleanup handles cancellation before connection and after resumption
  but before delivery, null binding, binding death, and unsuccessful binding.
- Boot startup checks the opt-in setting and overlay permission. Foreground
  location mode is requested only with foreground/background location permission
  and enabled location services. Platform startup restrictions are caught.
- ANT+ uses the PR's Peloton managed-network slot 1 approach. Diagnostic public/
  ANT-FS fallbacks and attempts to overwrite the shared radio network key were
  removed. Acquired secondary channels are released when setup fails.
- The unused ANT receiver-plugin library, ineffective device-name setting,
  machine-specific build paths, and global Android test-stub suppression were
  excluded.

## Unit-test failure investigation

The incoming branch enabled `unitTests.returnDefaultValues = true`, which hides
unmocked Android calls across the whole test suite. That option is not enabled
here. The eager Peloton device-model initialization mentioned in the PR was
already lazy in the Tread integration.

The incoming V1 lifecycle test failed under strict Android stubs. It constructed
a real Android `Intent` and repeatedly called `stop()` while polling for the bind,
which could cancel the operation it was waiting for. Deterministic tests now inject
the intent and control service callbacks and coroutine cancellation directly for
both V1 and Bike+ bindings. No sleep-based polling or global stub defaults are
needed.

## Validation

- 121 JVM unit tests passed, with zero failures, errors, or skipped tests.
- Coverage includes existing model/platform detection paths, Bike/Tread routing,
  BLE descriptor reads, both bike bindings, ANT+ packet bytes/counters/lifecycle,
  transport unit conversion, heart-rate disconnects, boot gating, and zone edges.
- `assembleDebug` and `lintDebug` passed. All 16 former `MissingPermission`
  errors are fixed; lint reports zero errors (49 warnings and 2 hints remain).
- Heart-rate Bluetooth access checks the grants required by the Android version
  before scanning, connecting, reading device names, discovering services, and
  enabling notifications. Each protected operation also handles revocation
  between the check and the call. Cleanup attempts disconnect and close
  independently, clears local state, and ignores late callbacks from old GATT
  connections. Tests cover denied/granted permissions, revocation during each
  operation, scan cleanup, and stale heart-rate prevention without lint suppression
  or relaxed Android test stubs. The existing manifest permissions are retained;
  see Android's [Bluetooth permission guidance](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).
- Used the locally available Gradle 8.14 distribution with offline dependencies;
  the local Gradle 9.0 wrapper cache is damaged.

Actual ANT+ pairing, boot behavior, and reconnect timing still require testing on
Peloton hardware. In particular, the ANT service reflection path depends on the
tablet's Radio Service implementation; JVM tests do not validate RF operation.

Source: [PR #76](https://github.com/doudar/grupetto/pull/76), by
[@adavila3](https://github.com/adavila3), credited using the verified commit author
identity `berto3 <davila.eng.17@gmail.com>`.
