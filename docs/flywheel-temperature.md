# Experimental flywheel temperature

The calibration page shows an **estimated effective flywheel temperature**, not a sensor reading.
Capture settings includes room temperature (default 72°F; accepted range 32–110°F, or 0–43.33°C).
The unit button inside the input converts the current unsaved number and switches the flywheel display.
Unit choice is remembered immediately, including after Cancel; Save still applies the room edit,
while Cancel discards that edit. Repeated toggles retain the unrounded draft, so they do not shift
the estimate or calibration. Existing room settings, stored samples and the fixed thermal reference
keep their original physical values. The experimental model itself continues to use Celsius internally.
Changing room temperature is a **baseline correction**, not a simulated physical room change:
the current flywheel estimate shifts by the same ambient delta, preserving its rise above room.
For example, 100°F at a 65°F room becomes 107°F at a 72°F room; a cold estimate moves from
65°F to 72°F immediately. The model first advances to the edit time on the previous baseline.
Cooling/heating then continues against the new room setting.
The live correction uses the shifted estimate immediately, but historical samples and their
temperature provenance are never rewritten, including delayed packets captured before the edit.
Capture presets do not change room temperature. Cancel discards unsaved room edits.
New temperature-tagged calibration cells now use the experimental normalization described in
[thermal calibration](thermal-calibration.md). Old unknown-temperature cells retain their prior behavior.
The heat estimator itself continues to use only raw native power, never its corrected output.

## Provisional model

`C dT/dt = P - H (T - room)` with C = 8000 J/K and H = 8 W/K.
These are experimental effective parameters, **not measured Peloton specifications**. The cooling
assumption is a constant fan; changing fan speed, position or wheel speed invalidates that assumption.
The time constant is 1000 seconds. Raw, uncorrected bike watts approximate brake heat; neither pedal
power nor temperature-corrected power is used. Raw bike power itself may be biased by temperature.
Acceleration, flywheel stored energy, drivetrain losses, brake hot spots, and changing airflow are
not resolved. A single effective temperature is not necessarily the temperature of the measured surface.

The exact constant-input update is `room + (T-room)*exp(-dt/1000) + (P/8)*(-expm1(-dt/1000))`.
This remains stable for long gaps and avoids small-step cancellation. At 200 W, a 65°F start reaches
about 102.6°F after 30 minutes and approaches 110°F; this is an illustration, **not a fit or a limit**.
At 400 W it can exceed 110°F. The observed 65→100–110°F ride cannot identify both parameters without
time, power history and fan conditions. Collect time-aligned temperature readings during warming and
cooling to fit them before relying on the experimental compensation. Shiny-metal infrared readings need care
with emissivity and reflected temperature; use a consistent, independently checked measurement method.

Peloton's [20250367501 patent application](https://patents.justia.com/patent/20250367501) discusses
temperature sensing near an eddy-current flywheel and temperature-dependent brake behavior. It does
not establish that this bike has such a sensor, or supply the constants above.

## Lifecycle, missing data and storage

The existing V1 native callback supplies observations before calibration correction, even when Record
and Activate are off. No extra binder client, motor command or resistance operation is added. Other
sensor paths without these frames show no live heat input. A process-owned one-second ticker updates
the display; Activity recreation and calibration dialogs do not restart the model.

Monotonic elapsed time drives runtime integration. Duplicate/out-of-order timestamps are ignored.
Only finite 0–3000 W native-source readings are accepted. Invalid input stops the heat hold. A valid
reading is held for at most 3 seconds; after that **zero heat input is assumed**, not confirmed idle.
The compact readout shows only estimated temperature and thermal-cell count. The information
dialog explains the experimental model, ambient-start assumption and missing-telemetry limits.
The live model no longer classifies or displays history uncertainty. Existing sample/export history
fields remain unchanged for schema compatibility and round-trip old values. New samples use the
conservative legacy `historyUnobserved=true` / `InterruptedHistory` marker; this means history is
not assessed, not that a new interruption was detected. No sample is labeled as known history.
These metadata fields do not affect heating/cooling, fitting, sample acceptance or corrected watts.
The 72 F room default applies only when no valid checkpoint exists; saved room settings are restored.
The fixed 65 F calibration reference is unchanged.

A version-1 JSON checkpoint (temperature, ambient, wall timestamp) lives in the separate
`experimental-flywheel-temperature` preferences. SharedPreferences atomically replaces the snapshot,
asynchronously, every 30 seconds and on room edits. Telemetry callbacks do no disk I/O. Abrupt process
death may lose up to 30 seconds. Restore applies zero-input cooling over wall-clock elapsed time,
and never replays last watts. Riding while the app is closed is missed.
Backwards clock changes apply no elapsed cooling; forward clock changes may overcool the estimate.
Invalid/unknown-version state starts at 72°F. No existing calibration/settings files are migrated,
reset by the estimator. The separate thermal-calibration format migration now adds immutable sample
temperatures and reference-gain metadata to calibration exports, with recovery copies of older files.

## Validation

Unit tests cover exact-step consistency, steady state, temperatures above 110°F, startup, stale and
invalid input, repeated/reordered timestamps, restart cooling, clock rollback, room edits and invalid
checkpoints. Emulator tests cover labeled estimates, room validation, save/cancel and preset behavior.
Live accuracy remains unvalidated. Installation requires a separate ride-status check and permission.
