# Pedal-referenced bike calibration capture

> Updated implementation: see [Grupetto power calibration](grupetto-power-calibration.md)
> for the 8-second guided/background collector, independent Record/Clear/Activate
> power table, and experimental guided sensor-board writer. The sections below
> retain the original implementation and hardware-investigation history.

On an original Bike (`peloton_platform=v1`), open **External Sensors → Calibrate
bike power to pedals** and enable the checkbox. Grupetto starts its ride overlay
service so capture can continue while another app is in front. Pair a power meter
first; warm up and zero it through its manufacturer's app. The input must be a
total-power reading (including a meter that internally doubles one leg), not an
undoubled single-leg stream.

This implementation **collects and fits only**. It contains no board-writing or
tablet-override methods. This follows the requested constraint: when calculated
values cannot be written to the board through a verified interface, retain the
candidate rather than applying a tablet override. Enabling the checkbox is not
a claim that calibration has been applied. The UI states the limitation next to
the checkbox and candidate results.

## Capture

Calibration uses a separate, temporary subscription to the same Affernet V1
service. The regular ride stream, external-power replacement, and broadcasting
continue unchanged. Only transactions 1 (register), 2 (unregister), and 11 (read
active Lxx array) are implemented in the calibration client. Transaction 11 takes
an output-array length, not an input array.

The extended parcel was verified against Affernet 2.4.490 (version 2040490).
Capture refuses other versions, unexpected V3 parcelables, missing frame identity,
or unknown power sources. It requires sensor-board power (source 1), so a tablet
override cannot accidentally become the reference for a board-calibration fit.
The active 31-point table must be positive and strictly increasing. It is read
again every 30 seconds; a change stops collection. Bike serial, meter address,
service version, and baseline table must match the saved session before resuming.

Each observation is a nonoverlapping steady effort lasting at least 11 elapsed
seconds with at least 10 distinct paired readings (nominally 12 samples at 1 Hz).
The UI rounds this instruction to at least 12 seconds. The first four seconds
are discarded to reduce the effect of meter/reporting lag; medians are taken over
at least six settled pairs. This is a steady-state comparison, not dynamic lag
estimation. Longer/variable meter delays remain a limitation.

Required ranges are 45–120 rpm and 30–1500 W. Cadence spread must be at most 4 rpm;
raw sensor spread at most 8% of the local Lxx gap, with a minimum allowance of two
AD units. Settled native and pedal power spreads must each be within the larger
of 12 W or 8% of their median. Bike data older than one second, pedal data older
than 1.5 seconds, repeated pedal packets, gaps, source changes, or sensor errors
cannot produce observations. Cadence/resistance changes restart settling.

## Fitting and review

The fitter uses measured native and pedal power versus raw AD in 5-rpm cadence
bands. It does not assume the sensor firmware uses the extracted tablet-side
Rexon/Tonic tables. Data are grouped locally in tenths of an original Lxx
interval. A cell needs three repeat efforts: two train its curve and every third
effort is reserved for validation. Curves must be strictly increasing in raw AD,
native watts, and pedal watts; non-invertible curves are omitted.

For a baseline point L[i], interpolate native watts at that point and find the
raw AD where measured pedal power reaches those watts. That AD is the proposed
replacement point. Repeat across cadence bands. At least two bands separated by
10 rpm must support the point. Do not extrapolate beyond observations or bridge
an AD gap larger than two local Lxx intervals. Cadence proposals differing by
more than 25% of the local interval leave the point unchanged and report a
conflict. Otherwise use their median; reject corrections larger than 75% of the
local interval. Preserve endpoints and all unsupported points, and reject a
complete candidate whose points cross.

Candidate validation maps observed AD through the proposed table back into the
original table's coordinate system, then estimates native power from training
curves. Only changed intervals with local curve coverage are evaluated. A
candidate is ready for **review**, never application, only with at least four
supported interior points, 12 held-out efforts, and two cadence bands; estimated
RMSE must improve at least 10% and 1 W, without worsening aggregate squared error
in any evaluated cadence band.

The coordinate-warp prediction is a hypothesis about how the board uses Lxx;
physical before/after validation would be required before supporting board
writes. Narrow cadence bins and steady efforts reduce confounding but do not
eliminate temperature drift, pedal error, or cadence quantization. A one-dimensional
Lxx mapping cannot fix every cadence-dependent model error. The UI exposes
conflicts instead of claiming that a single calibration point can solve them.

## Persistence and device writes

Each accepted observation is saved atomically to private app storage in
`bike-power-calibration.json`, including baseline, bike/meter identity, samples,
candidate, supported points, and estimated validation errors. Up to 3600 steady
efforts are retained. Restarting the app loads saved results with collection
disabled. Pausing retains data; clearing requires confirmation and only removes
this capture file. Existing service calibration is never changed.

The inspected service's `saveLxxValues(int[])` saves a tablet override. Its
sensor-programming command instead captures the sensor's current physical AD at
an index; it does not accept a supplied AD value. Also, the inspected calibration
session selects board programming only if the sensor Lxx table is invalid.
Neither behavior provides a verified arbitrary board-table upload. There is no
fallback that invalidates factory calibration, enters fake mode, modifies the
service, or silently switches to tablet calibration.

## Verification

The debug APK builds, all 262 JVM tests pass, and Android lint completes without
errors. Five instrumentation tests pass on the Kindle test tablet, covering the
observed parcel layout, rejection of unknown telemetry, read-only Binder
transactions, atomic storage including backup recovery, and the collection UI.
Synthetic fitter tests cover recoverable offsets, cadence conflicts, sparse
coverage, excessive corrections, and failed held-out validation. These checks do
not establish accuracy on a real ride or verify the board's calibration mapping.
The build has not been installed on the bike, and no bike calibration was written.

## Further board-access investigation (2026-10-18)

Read-only ADB inspection confirmed `/dev/ttyO0` is owned by `system:system`, mode
0660, with SELinux enforcing. Querying Android SerialService's port list as shell
fails with missing `android.permission.SERIAL_PORT`; shell cannot simply replace
Affernet as the serial owner. Affernet's V1 configuration uses 19200 baud. Its
JNI library supplies serial I/O, while the Lxx packet constructors are Java code.
No arbitrary-value board setter was found in the inspected V1 interface or packet
constructors. This does not prove that no undocumented board command exists.

The inspected `V1Native` constructors establish the following wire formats (hex;
`i` is the point index 0–30; arithmetic wraps to a byte):

| Operation | Packet | Meaning established by caller |
| --- | --- | --- |
| Read raw AD | `F5 4A 3F F6` | Read current physical sensor position |
| Read Lxx point | `F7 i (F7+i) F6` | Read stored point |
| Program Lxx point | `FC i (FC+i) F6` | Capture current physical AD at index |

The programming packet has no field carrying an arbitrary replacement AD. None
of the programming packets were sent. Board acceptance on an already-valid
table, sequencing requirements, persistence, and restoration are unverified.
Affernet's `startCalibration(boolean)` gate is application logic; its presence
does not establish whether the board itself imposes the same restriction.

A second installed package, `com.peloton.service.SensorData` 2.8.3016, offers a
potential alternative transport. Its exported `com.peloton.sensor.SensorService`
has no service permission in its manifest. `NON_REPEATING_REQUEST_COMMAND`
(Messenger message 12) accepts `command` and `requestId` strings plus `replyTo`.
The inspected command handler and factory do not check caller identity;
`BikeCommand` → `SensorDataReceiver.writeRequestAndStartReading` →
`UartUtils.getSendData` → `SerialHandlerManager.writeData` passes comma-separated
hex bytes to `/dev/ttyO0`. Although the package declares a signature sensor
permission, that declaration alone does not prove this entry point enforces it.
This is an offline source finding, not a verified working bypass or board writer.

A bounded shell helper with only four fixed read packets was compiled and pushed
to `/data/local/tmp/grupetto-legacy-read-probe.dex`. `app_process` returned
`Killed` before a connection or response was reported. Therefore no successful
legacy-service read is established. Follow-up ADB log/connectivity checks and a
retry were not executed because automatic approval review failed at model
capacity. The helper file may remain; service state after the attempt could not
be checked. No reset, calibration write, root operation, or firmware flash was
requested. Further probing should use a small ordinary Android diagnostic app,
read-only packet allowlisting, bounded lifetime, and explicit serial ownership;
two independent readers can consume each other's responses.

SmartSpin2k's inspected local firmware uses 19200 8N1 aux serial and the same
`F5 ... F6` framing. `SS2K::txSerial()` currently polls only power, cadence, and
resistance; it implements no Lxx writer. Official documentation describes
[headless mode](https://docs.smartspin2k.com/documentation/troubleshooting.html#peloton-data-only-during-a-ride)
as allowing SmartSpin2k to request bike data with the tablet cable unplugged.
The user has no SmartSpin2k connected in that mode, and none was contacted,
reconfigured, moved, or flashed during this investigation.

A possible future route is to move the resistance knob until measured raw AD
equals a fitted candidate, issue the capture-at-index command, and read back the
stored point. This could program fitted values physically without an arbitrary
numeric-write command. It remains a hypothesis requiring a controlled hardware
test. First establish reliable repeated raw-AD and full-table reads, retain the
original board table, and establish a restoration procedure; then verify one
point's capture semantics and persistence before considering a full sequence.
Do not invalidate factory calibration to enable the Affernet path. SmartSpin2k
motor steps alone must not be treated as raw AD, and a single Lxx mapping still
cannot resolve incompatible cadence-dependent errors.

### Successful live SensorData read (2026-10-18)

A small ordinary Android diagnostic app (`com.grupetto.sensorreadprobe`) was
built and installed with a fixed allowlist of five read packets and a 12-second
timeout. It accepted no caller-supplied bytes and contained no calibration-write
commands. The first run bound successfully but returned no readings. Logcat
reported `SerialServiceJNI: could not open /dev/ttyO0` and
`SerialHandlerManager: java.io.IOException: Could not open serial port /dev/ttyO0`.
The app unbound and `dumpsys activity services` showed no active legacy service.

Inspection of Affernet's native `open_serial` implementation found ioctl
`0x540C` (`TIOCEXCL`), which requests exclusive serial access. A second run briefly
stopped Affernet to release the port, then ran the identical fixed read requests.
All five returned through SensorData's Messenger response event 14:

| Request | Returned value | Raw response bytes (hex) |
| --- | --- | --- |
| Cadence | 0 RPM | `F1 41 03 30 30 30 C5 F6` |
| Power | 0 W | `F1 44 05 30 30 30 30 30 2A F6` |
| Resistance | 31 | `F1 49 03 31 33 30 D1 F6` |
| Raw sensor AD | 556 | `F1 4A 04 36 35 35 30 0F F6` |
| Stored calibration point L00 | 173 | `F1 F7 04 33 37 31 30 B7 F6` |

Cadence and resistance match the user's stationary-bike reference. Independent
offline checks verified packet lengths, ASCII digits in least-significant-first
order, and additive byte checksums for all five responses. The probe completed
and unbound; Affernet was explicitly restarted in a `finally` block, and its
running process was confirmed. SensorData's raw-command read path is now verified
on this bike when Affernet releases the port. This does **not** verify arbitrary
numeric board writes or capture-command behavior. No calibration command was
sent, and the collection-only feature remains unchanged.

Local evidence is retained in ignored
`build/affernet-probe/android-probe/result-exclusive.txt` and
`verified-readings.json`, with diagnostic source and APK alongside them. The
diagnostic app remains installed but is not running a capture.

### Original Bike telemetry fallback

Grupetto discovers enabled services advertising the Affernet V1 or SensorData
intent contracts across packages. Intent-based Android package visibility queries
allow renamed implementations to be found without adding their package names.
Affernet V1 has priority; SensorData is considered only when no enabled Affernet V1
provider is discovered. An inaccessible Affernet provider still prevents a legacy
serial takeover. Accessible system implementations are preferred, followed by other
implementations in deterministic component-name order. This selection only applies
to the original Bike; Bike+ and Tread keep their existing interfaces.

The fallback binds explicitly to the discovered component (on the tested bike,
`com.peloton.sensor.SensorService`) with the
`com.peloton.sensor.category.BIKE` category and uses Messenger request 12/reply 14.
It polls only the three fixed cadence, power, and resistance read commands above,
one at a time, with a 100 ms pause between requests and a 1.5 second reply timeout.
Request IDs exclude late responses; packet framing, command, length, digits, and
checksum must validate before a value is published. `TIME_OUT` and invalid replies
produce no reading, allowing the existing dead-sensor warning to detect failures.
Peloton SensorData 2.8.3016's `V1DataExtractor.atof` confirms the raw power value is
in tenths of a watt; cadence and resistance are whole units. The Binder bundle's
`data` field already has this scaling, but the fallback decodes the validated raw
packet itself. Nonzero scaling is covered by synthetic packets, not a live ride.

The returned Binder descriptor must match the advertised protocol before telemetry
requests are sent. Binding has a five-second timeout, followed by up to six seconds
to receive all three metrics. An unsuccessful attempt is unbound before another
provider of the selected protocol is tried. No providers are bound concurrently.
Unknown Binder interfaces are logged and rejected; a new wire protocol still needs
an adapter even if developer permissions reveal the service hosting it.

The three flows share one connection and poller. Stopping the interface cancels
polling, closes the response handler, and unbinds. There are no service-side
repeating commands to leave running. Affernet binding failures or missing
callbacks do not trigger a serial takeover; Grupetto never force-stops Affernet.
This is telemetry support only and adds no calibration-write capability. Existing
external power-meter selection still wraps this native telemetry interface.

### Developer-granted service diagnostics (2026-10-19)

The `BikeServices` log tag is available in release and debug builds. It records
discovered component/process/UID/permission metadata, the component actually bound,
its Binder interface, and whether all three metrics were received. Connection
failures and a full failed SensorData read cycle after successful telemetry produce
a diagnostic snapshot. The latter logs once per continuous failure episode.

Grupetto declares optional `DUMP`, `READ_LOGS`, and `PACKAGE_USAGE_STATS` permissions.
They are not required for telemetry. OpenPelo/ADB can provision them after installing
this build; OpenPelo's current `install -g` path alone must not be assumed to grant
all these development permissions and app-ops. With the intended device selected:

```sh
adb shell pm grant com.spop.poverlay android.permission.DUMP
adb shell pm grant com.spop.poverlay android.permission.READ_LOGS
adb shell pm grant com.spop.poverlay android.permission.PACKAGE_USAGE_STATS
adb shell appops set com.spop.poverlay GET_USAGE_STATS allow
adb logcat -s BikeServices
```

When granted, diagnostics use bounded read-only `dumpsys activity services` and
filtered serial-warning `logcat` commands. Logs retain relevant service identities,
PIDs, process names, and binding actions rather than full dumps with extras. Serial
log entries are explicitly labeled as potentially older than the current attempt.
Unrecognized related services can be reported by the privileged snapshot, but are
not sent guessed protocol transactions. Neither an active service nor a missing
reply alone proves that service owns the UART; the log labels ownership unverified.

A temporary diagnostic APK on the real Peloton verified that `DUMP` and `READ_LOGS`
are grantable. `DUMP` alone was insufficient: ActivityManager explicitly required
`PACKAGE_USAGE_STATS`. After the user approved adding usage access, the same helper
successfully reported `com.onepeloton.affernetservice/.AffernetService`, PID 9938,
from inside an ordinary app process. The helper was uninstalled immediately after
each run; final package-list verification showed it absent. No sensor service was
bound or stopped, no UART was opened, and no calibration command was sent. Evidence
is in ignored `build/service-diagnostics-probe/result.txt` and
`result-with-usage.txt`. The production Grupetto app on the bike was not replaced
or granted these optional permissions during the tests.

ADB remains subject to SELinux: on this bike, `/proc/9938/fd` inspection was denied
even to shell UID 2000. These diagnostics improve discovery and evidence without
claiming root access or universal serial-owner introspection.
