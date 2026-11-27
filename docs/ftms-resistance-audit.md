# FTMS resistance contract and SmartSpin2k compatibility

Reviewed 2026-11-08. There is one wire format, with no compatibility switch or
peer-name heuristic. Existing SmartSpin2k clients need no configuration or firmware
update for these changes.

## Units and capabilities

| Surface | Representation | Example: Peloton level 100 |
| --- | --- | --- |
| Manual slider, controller, motor writer | Integer points, 0–100, step 1 | `100` |
| Indoor Bike Data (0x2AD2) resistance | Legacy GATT signed 16-bit LE, whole points | `64 00` |
| Supported Resistance Level Range (0x2AD6) | Six-byte min/max/increment, tenths | `00 00 E8 03 0A 00` |
| Set Target Resistance (0x04) | Signed 16-bit LE, tenths; only whole-point targets accepted | `04 E8 03` |
| Target Resistance Changed status (0x07) | Signed 16-bit LE, tenths | `07 E8 03` |

`ResistanceLimits` supplies the bounds to the UI, controller, motor writer,
telemetry encoder and range encoder. The full signed operand space is tested
against the advertised range/increment: exactly 101 resistance targets succeed.
Different raw values in telemetry and commands represent the same physical level;
telemetry must not be multiplied by ten.

All bikes expose the same range. Read-only bikes keep their resistance target
feature bit clear and reject resistance commands. This optional characteristic is
allowed by FTMS Table 3.1, condition C.3, and is required by existing SmartSpin2k
homing. Tread exposes neither bike resistance data nor a resistance range.
Bike+/G700 control still requires fresh telemetry, ownership for remote commands,
and at least 25 rpm to move. The current develop branch's ERG modeled ramps and
settle behavior are preserved; ordinary feedback/manual movement remains limited
to three points per second.

Local manual targets, resistance shifts and manual-mode knob feedback now emit
Target Resistance Changed through both BLE and DirCon. Local ERG target changes
emit Target Power Changed. Remote procedure results are not duplicated by the
state observer, and Control Permission Lost is retained. The controller snapshot
and remote procedure baseline are synchronized so intermediate mode changes or
queued older state emissions cannot overwrite the latest target status.

## SIG references and the unresolved publication conflict

- [Fitness Machine Service 1.0.1](https://www.bluetooth.com/specifications/specs/html/?src=ftms-v1-0-1_1756429637%2FFTMS_v1.0.1%2Fout%2Fen%2Findex-en.html):
  Table 3.1 C.3 permits a range without target control; section 4.17.1 requires
  notification of user-made status changes. Table 4.26 specifies resistance status
  as signed 16-bit tenths, while Table 4.15 still lists an inconsistent UINT8
  control operand.
- [FTMS test suite p6](https://files.bluetooth.com/wp-content/uploads/dlm_uploads/2024/10/FTMS.TS_.p6.pdf):
  CR/BV-05-C requires a six-octet resistance range; CW/BV-05-C specifies signed
  16-bit control; FMSN/BV-25-C specifies signed 16-bit status with 0.1 resolution.
  The control/range/status implementation follows that test contract.
- [GATT Specification Supplement, 2026-09-09](https://btprodspecificationrefs.blob.core.windows.net/gatt-specification-supplement/GATT_Specification_Supplement.pdf):
  sections 3.139 and 3.231 instead specify one-byte telemetry resistance and a
  three-byte range. Its resistance metadata also lists `d = 1` alongside
  `Unit is 1`. These definitions are not internally consistent with the FTMS test
  contract above.

The retained two-byte whole-point telemetry is the established legacy GATT layout
consumed by SmartSpin2k. This is **not a claim of full conformance to the current
GATT supplement**. Changing width without a negotiated client capability would
break existing receivers. A future migration needs an explicit receiver protocol
and independent SIG clarification, not guesses based on a central's name or MAC.

## SmartSpin2k source verification

Inspected local SmartSpin2k revision
`bac99a553c84e0297175bd053e607372d189f997`, with unchanged parser files:

- `lib/SS2K/src/sensors/FitnessMachineIndoorBikeData.cpp`: resistance has width 2,
  signed decoding and resolution 1.0. Cadence and power follow the existing field
  offsets. A one-byte resistance field would consume a power byte as resistance;
  multiplying resistance by ten would produce levels up to 1000.
- `src/BLE_Client.cpp`: identifies Grupetto through the Device Information
  manufacturer, reads a six-byte range and normalizes min/max by the increment.
  The new unified range gives increment 1.0 and min/max 0/100. The former original
  Bike range gave min/max 1/100 only because its raw increment was also unscaled.
- `src/SensorCollector.cpp`: publishes Grupetto resistance as real feedback.
- `src/Stepper.cpp`: FTMS transition homing needs a range covering at least 1–99.
  Removing the optional read-only range would leave the initial 0/0 range and
  disable this path. Keep the characteristic and the manufacturer identity.

The production Kotlin encoder's 202 packets (all levels 0–100, with and without
an appended heart-rate field) were fed to the actual compiled SmartSpin2k C++
decoder. Resistance, power, cadence and speed all decoded correctly. Heart-rate
field bytes/offsets are covered by Grupetto tests; this probe does not certify
SmartSpin2k's heart-rate accessor. No SmartSpin2k source or firmware was changed.

## Sensor semantics and validation limits

The previous rounding correction is preserved: resistance uses the latest finite
sensor-interface reading per transmit interval, rounds rather than truncates,
and clamps to 0–100. Non-finite input encodes zero. Zero is also a valid target,
so it cannot uniquely identify missing data.

Original Bike supplies `currentResistance`; Bike+ supplies the mainboard's
`targetResistance`. The latter is target feedback, not proof of physical motor
position. Both interfaces retain their minimum-of-three spike filter; increasing
levels can lag until three readings arrive. No source substitution was made
without physical hardware evidence.

Regression coverage includes packet bytes, rounding boundaries, every integer
level, all 65,536 signed command operands, range normalization for SmartSpin2k,
local target status, duplicate suppression, permissions, and the actual GATT
characteristic values on both controllable and read-only devices. Physical
Bike+/CrossTrainer motor behavior and over-the-air receiving-client captures
remain unverified.

Validation on 2026-11-08 against develop `4f8ec85`: all 604 JVM tests passed; debug and instrumentation APKs
built; all six FTMS Android integration tests passed on the KFRAPWI Kindle with
a fake motor. The native SmartSpin2k decoder probe passed all 202 packets.
`lintDebug` reports one pre-existing `NotificationPermission` error at
`CalibrationCaptureService.kt:36`; the same call exists in the unchanged HEAD
version. No lint suppression or unrelated calibration change was added.
