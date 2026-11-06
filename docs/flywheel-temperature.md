# Experimental flywheel temperature

The calibration page shows an **estimated effective flywheel temperature**, not a sensor reading.
Capture settings includes room temperature in Fahrenheit (default 65°F; accepted range 32–110°F).
Changing room temperature preserves the absolute flywheel estimate and changes subsequent cooling.
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
The display explicitly says “no live power; cooling assumed.” Cold start assumes ambient and all
estimates have unobserved initial history; the information dialog explains this uncertainty.

A version-1 JSON checkpoint (temperature, ambient, wall timestamp) lives in the separate
`experimental-flywheel-temperature` preferences. SharedPreferences atomically replaces the snapshot,
asynchronously, every 30 seconds and on room edits. Telemetry callbacks do no disk I/O. Abrupt process
death may lose up to 30 seconds. Restore applies zero-input cooling over wall-clock elapsed time,
never replays last watts, and marks history unobserved. Riding while the app is closed is missed.
Backwards clock changes apply no elapsed cooling; forward clock changes may overcool the estimate.
Invalid/unknown-version state starts at 65°F. No existing calibration/settings files are migrated,
reset by the estimator. The separate thermal-calibration format migration now adds immutable sample
temperatures and reference-gain metadata to calibration exports, with recovery copies of older files.

## Validation

Unit tests cover exact-step consistency, steady state, temperatures above 110°F, startup, stale and
invalid input, repeated/reordered timestamps, restart cooling, clock rollback, room edits and invalid
checkpoints. Emulator tests cover labeled estimates, room validation, save/cancel and preset behavior.
Live accuracy remains unvalidated. Installation requires a separate ride-status check and permission.
