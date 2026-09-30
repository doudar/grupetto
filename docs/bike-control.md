# Bike+ and CrossTrainer control

The motor transaction and proportional ERG approach are based on
[Douglas Bryant (dwj300)'s PR #50](https://github.com/doudar/grupetto/pull/50).
The implementation is adapted to the shared sensor lifecycle and BLE/DirCon
transports, rather than merging the older branch wholesale.

## Device gating

Physical motor control requires Peloton hardware with the confirmed `titan`
platform, or a G700/PLTN-ATR CrossTrainer model. Known Tread, Tread+, Row and
original Bike platforms are excluded. A generic Topaz/PLTN-TTR01 tablet with
an unknown platform is not sufficient. Controls appear only after valid, fresh
telemetry arrives. CrossTrainer uses the existing Affernet sensor connection.
The motor writer uses interface token `SERVICE_ACTION`, binder transaction 7,
one integer resistance argument, and the remote exception reply.

## Behavior

- ERG: 25–1000 W; adjustable proportional gain 0.001–0.030, default 0.007.
  Power smoothing uses a two-second time constant with a three-watt deadband.
- Sim: start from current resistance as the flat-road baseline. Grade changes
  add two Peloton resistance points per percentage point. Wind, rolling
  resistance and wind resistance coefficient are converted to equivalent grade
  using fixed reference values of 30 km/h and 85 kg. This is a resistance/feel
  model, not calibrated road physics for the rider. It deliberately does not
  feed the existing power-derived broadcast speed back into motor control.
- Shifts add/subtract a persistent resistance offset (1–10 points, default 2)
  in Sim only. Grade updates preserve shifts; leaving Sim clears the offset.
  Shift saturation does not accumulate hidden shifts beyond the motor limits.
- All modes clamp to 0–100 Peloton resistance and limit movement to three
  points per second. Below 25 rpm, commands pause without accumulating ERG
  corrections. Pedaling again resumes a still-active target.
- Data older than 1.5 seconds, sensor loss, motor-write failure, controller
  disconnect, or unacknowledged/overridden resistance stops automatic control.
  A new target is required after these events. The physical knob is not fought:
  no further write occurs while the reported target differs from the expected
  target; after 1.5 seconds control is relinquished.
- Manual in settings always relinquishes control. A second BLE/DirCon/local
  controller cannot take ownership until the owner releases/disconnects or
  Manual is selected. BLE shutdown/restart releases BLE ownership.

## FTMS

Implemented procedures: Request Control, Reset, Set Target Resistance, Set
Target Power, Start/Resume, Stop/Pause, and Indoor Bike Simulation Parameters.
Pause retains the target for explicit Resume; Stop/Reset discard it. No motor
movement is commanded while the rider is below the cadence threshold.

Bike+/CrossTrainer expose power/resistance ranges and Machine Status. The
advertised target feature bits are resistance, power, and simulation only.
Power is SINT16 watts. Resistance uses the corrected SINT16 tenths format:
0–1000 in increments of 10 means Peloton levels 0–100. Existing Indoor Bike
Data resistance telemetry remains in whole levels. The
[Bluetooth SIG FTMS test suite p6](https://files.bluetooth.com/wp-content/uploads/dlm_uploads/2024/10/FTMS.TS_.p6.pdf)
specifies SINT16 for resistance control (BV-05-C) and corrected resistance
status (BV-25-C); older published tables inconsistently describe UINT8.

Malformed lengths, unsupported operations, signed/out-of-range targets, and
requests without ownership are rejected. BLE rejects prepared/offset writes.
DirCon uses the same parser/controller, with a unique identity per TCP session
and control-point responses routed only to the requesting subscribed session.
Targets are accepted asynchronously; a subsequent motor failure relinquishes
control and emits Control Permission Lost.

## Developer emulation

Five taps on the version label opens the model selector. Choosing a model
restarts the app so the overlay, settings, and sensor selection stay consistent.
Selection persists in separate developer preferences. Each emulated sensor is
purely synthetic: it never binds Affernet or writes a real motor. Bluetooth,
DirCon, ANT+ transmission and external sensor discovery are disabled during emulation.
The original connection/overlay preferences are preserved in a separate file.
Use **Detected hardware** to return to normal. Emulation also works in release
builds, so the hidden menu can be used on an installed tablet.

## External power

External Sensors supports one Bluetooth Cycling Power meter alongside the existing
heart-rate connection. Discovery filters for service 0x1818 and subscribes to
measurement 0x2A63. The selected device is saved for reconnection; explicit
Disconnect clears the automatic selection, while Forget also removes the saved
device. Truncated packets do not refresh the reading timestamp. Signed negative
watts are decoded correctly and clamped to zero for ride metrics.

Reserved flag bits and extra trailing bytes are ignored, as required by
[Cycling Power Profile 1.1, section 4.5](https://www.bluetooth.org/DocMan/handlers/DownloadDoc.ashx?doc_id=412769).
The P715 sends flags `0x602f`; rejecting its reserved bits previously discarded
valid watt readings and caused a 12-second reconnect loop. Regression tests use
an actual captured packet and verify sustained notifications keep the link alive.
Connection stages, subscription status, the first packet, and data timeouts are
logged under the `PowerMeter` tag, including in release builds. On Peloton builds
that filter informational logs, temporarily enable them with
`adb shell setprop log.tag.PowerMeter INFO` and read `adb logcat -s PowerMeter`.

Fresh external watts replace native watts in the shared overlay/broadcast stream
and ERG feedback. Cadence, resistance, and speed keep their existing bike sources.
Native watts remain visible as a smaller **Peloton … W** line beneath external
watts in both overlay sizes. The Peloton service watchdog still observes native
telemetry, so a working external meter cannot conceal a stalled bike connection.
Freshness uses the current monotonic clock on both packet arrivals and periodic
checks; comparing a new packet against a previous timer tick incorrectly made
the source and comparison flicker off between updates.

After three seconds without a valid packet, display/broadcast falls back to native
watts and hides the comparison. After twelve seconds, the meter connection is
released for reconnection. Any source change—including loss while ERG is paused—
disarms ERG and releases ownership; a new explicit target is required. Simulation
does not depend on external watts and remains active through source changes.

## Validation

- 201 JVM unit tests pass with strict Android stubs; `returnDefaultValues`
  remains disabled. New coverage includes 18 device-gating cases, motor parcel
  arguments/recycling/errors, FTMS ownership/validation, stale data, cadence,
  manual override, rate limits, pause/resume, simulation, and shifts. External
  power tests cover signed/optional packet parsing, zero watts, freshness,
  fallback, native comparison, source changes, ERG direction, notification
  subscription, permission loss, late callbacks, and reconnect timing.
- Three Android integration tests pass on the attached Amazon Kindle
  (KFRAPWI, Android 11), using real GATT characteristic objects and a fake motor.
  They cover advertised capabilities/ranges, read-only Bike/Tread, shared
  BLE/DirCon procedure handling, ERG status, and unchanged telemetry scaling.
- Two Compose tests pass on the Kindle: the native-power comparison appears
  below external watts, updates and disappears on fallback, and the minimized
  comparison plus both shifters fit on screen.
- Debug APK build and lint pass (zero errors). The Kindle dashboard, developer
  menu, Bike+ tuning dialog, heart-rate dialog, and both overlay sizes were
  visually checked at 1280×800, density 213. A simulated shift changed resistance
  from 40 to 42. The visual pass caught and corrected the gain slider range.
- A physical P715 power meter was verified on an original Peloton Bike
  (PLTN-RB1VO-2, Android 11) using the release build. Service discovery and
  notification subscription succeeded, and live external watts remained active
  beyond a minute without the previous 12-second reconnect loop.
- Physical motor behavior, external-meter ERG feedback, and training-app
  interoperability on actual Bike+ and CrossTrainer hardware still need
  validation. No motor commands were sent to physical Peloton hardware during
  this work.
