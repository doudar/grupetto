# Experimental flywheel temperature

The calibration page shows an **estimated aluminum brake-disc temperature**, not a sensor reading.
Capture settings includes room temperature (default 72°F; accepted range 32–110°F, or 0–43.33°C).
The unit button inside the input converts the current unsaved number and switches the flywheel display.
Unit choice is remembered immediately, including after Cancel; Save still applies the room edit,
while Cancel discards that edit. Repeated toggles retain the unrounded draft, so they do not shift
the estimate or calibration. Existing room settings, stored samples and the fixed thermal reference
keep their original physical values. The experimental model itself continues to use Celsius internally.
Changing room temperature is a **baseline correction**, not a simulated physical room change:
both disc and hub estimates shift by the same ambient delta, preserving their rises above room
and their temperature difference.
For example, 100°F at a 65°F room becomes 107°F at a 72°F room; a cold estimate moves from
65°F to 72°F immediately. The model first advances to the edit time on the previous baseline.
Cooling/heating then continues against the new room setting.
The live correction uses the shifted estimate immediately, but historical samples and their
temperature provenance are never rewritten, including delayed packets captured before the edit.
Capture presets do not change room temperature. Cancel discards unsaved room edits.
New temperature-tagged calibration cells now use the experimental normalization described in
[thermal calibration](thermal-calibration.md). Old unknown-temperature cells retain their prior behavior.
The heat estimator itself continues to use only raw native power, never its corrected output.

## Provisional two-node model (heat estimator version 2)

The outer brake disc is **assumed aluminum**, and the hub **assumed steel**, following the user's
construction hypothesis. This is not a material assay. A painted, bolted interface can add thermal
resistance, but its actual conductance has not been measured. All estimated brake heat enters the disc:

```
Cd * dTd/dt = P - Hd * (Td - room) - G * (Td - Th)
Ch * dTh/dt = G * (Td - Th) - Hh * (Th - room)
Cd = 2000 J/K, Ch = 6000 J/K
G = 8 W/K, Hd = 4 W/K, Hh = 4 W/K
```

The displayed and newly recorded temperature is **Td**, the disc estimate. Th is a latent hub state,
not a second sensor reading. The total effective capacity remains 8000 J/K, with a smaller heated
region that responds faster and a hub that retains heat. Coupling transfers equal and opposite heat
between the nodes. A hot disc/cooler hub can cool quickly at first and have a slower tail; a sufficiently
hot hub can also rewarm the disc after input stops. We do not force either node to cool monotonically.

These are provisional effective constants, not measured masses, Peloton specifications or uniquely
identified physical parameters. Naming the materials does not determine the heat capacities. Ambient
losses still assume constant airflow; fan, wheel speed, contact resistance, emissivity, power bias,
acceleration and drivetrain losses are not separately modeled. Raw, uncorrected bike watts approximate
brake heat; pedal watts and temperature-corrected watts are never fed back into this estimator.

The exact constant-input two-state solution uses negative thermal eigenvalues and `expm1`, so it stays
stable over long gaps and precise over short updates. The decay modes are approximately 142 and 1058
seconds. Although Hd+Hh remains 8 W/K, the cooler hub makes the effective steady disc loss
`Hd + G*Hh/(G+Hh) = 6.667 W/K`: at 200 W and a 69 F room, the steady disc is 123 F and hub is 105 F.
These are model illustrations, not temperature limits or accuracy promises.

The 2026-11-07 recordings support a faster disc response in retrospective blocked replay. The
conservative capacity split was chosen instead of the smallest training-error capacity. The apparent
improvement depends on uncertain power/ambient/IR inputs, and multiple coupling/loss combinations fit
similarly. See [replay assumptions and comparisons](flywheel-thermal-replay.md). The recordings do not
contain a confirmed zero-input cooldown, and the camera center spot is not an identified hub temperature.
No additional ride test is required or scheduled by this change.

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

A version-2 JSON checkpoint (disc temperature, hub temperature, ambient, wall timestamp) lives in the
same separate `experimental-flywheel-temperature` preferences. A valid version-1 snapshot seeds both
nodes from its one saved estimate because the old model contains no gradient history. A version-2
snapshot must contain a valid hub value; malformed or unknown-version snapshots fall back safely.
SharedPreferences atomically replaces the snapshot,
asynchronously, every 30 seconds and on room edits. Telemetry callbacks do no disk I/O. Abrupt process
death may lose up to 30 seconds. Restore applies coupled zero-input cooling to both nodes over
wall-clock elapsed time,
and never replays last watts. A valid saved gradient is retained across process restarts. Riding while
the app is closed is missed.
Backwards clock changes apply no elapsed cooling; forward clock changes may overcool the estimate.
Invalid/unknown-version state starts at 72°F. No existing calibration/settings files are migrated,
reset by the estimator. The separate thermal-calibration format migration now adds immutable sample
temperatures and reference-gain metadata to calibration exports, with recovery copies of older files.

## Validation

Unit tests cover exact-step consistency against independent numerical integration, energy balance,
steady state, faster response than the 8000/8 and 6400/8 baselines, two-stage cooling and hub-to-disc
rewarming, temperatures above 110 F, startup without telemetry, stale and invalid input,
repeated/reordered timestamps, both-node restart cooling, V1 migration, clock rollback, room edits,
invalid checkpoints and mixed old/new calibration provenance. Instrumented tests cover checkpoint JSON,
calibration serialization, labeled estimates, room validation, save/cancel and preset behavior.
Live accuracy remains unvalidated. Installation requires a separate ride-status check and permission.
