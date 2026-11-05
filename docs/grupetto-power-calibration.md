# Grupetto power calibration

External Sensors → **Calibration** opens the table with **Record**, **Activate** and
**Clear** controls. The main screen and notification Show actions always open this
table. Free riding records directly here; there is no separate Free ride screen.
**Guided capture** remains available, with **Table view** returning to free riding.
Guided capture and table recording use the same paired,
uncorrected bike/pedal observations. With verified calibration active, the overlay
and console show corrected bike watts even with pedals connected. Displayed
raw and corrected bike numbers use the same non-overlapping 900 ms average of
actual bike packets, shared with table and capture screens. Pedal updates do not
recount bike packets; switching correction source discards a partial mixed bucket.
Calibration collection and ERG feedback remain unaveraged. The overlay's
power graph and power-derived stats follow that displayed stream, and pedal watts
appear underneath for comparison. BLE/DirCon FTMS and Cycling Power use the same
selected value and integer rounding as the large overlay number, without a second
power smoothing pass. ANT+ receives the same selected stream. ERG feedback still
uses the connected pedals. If correction is unavailable, display and broadcast fall back to pedals,
then bike watts. The raw BIKE number in capture remains uncorrected.
Clearing this option does not clear the board-fit capture
or change the sensor board. Activation is explicit and persists across restarts;
recording pauses after a process restart. With Record and Activate both on,
each usable fit automatically replaces the active snapshot after a saved effort,
without requiring a lower error score than the previous table or raw bike power.
With recording off, the snapshot stays fixed. **Apply latest fit** appears on the
Table page only when a usable draft differs from the active snapshot (for example,
a refit after restart or a failed automatic update). It is not a routine save step. Turning Activate off prevents automatic activation.
Turning it off retains the last usable snapshot. If a later draft has no usable cell,
Activate can reuse that saved snapshot for the same bike and baseline; the
controls show which saved effort count will be used and why the draft is not ready.
Record and Start capture require a connected meter with fresh readings. A dropout
resets the collection window; an already running session waits for reconnection.
The correction grid uses a fixed continuous **-10% bright red → 0% neutral → +10%
deep blue** scale. Negative reduces bike overestimation; positive increases bike
underestimation. Percentages are relative to raw bike watts and come directly from
the fitted gain: `100 * (exp(logGain) - 1)`. Colors saturate at the ends without
changing the actual correction. The grid follows the active table, or the
activation preview when off. Areas without measurements within two baseline
intervals and 10 rpm remain gray, not zero. Before/After average error stays in watts.
The correction grid pulses a solid fill in the cell at the rider's actual resistance and cadence:
cyan while viewing or paused, orange while settling, yellow while holding, and green when saved. A segmented
progress bar and seconds-left label follow the configured effort duration.
The live pulse does not require recording, activation, or an external meter. It disappears
when raw bike data is stale, cadence is out of range, or the baseline differs. The table screen shows live raw bike
watts, corrected watts, pedal watts, cadence and resistance even while recording
is paused. Fit messages and validation details are available from the information
button beside **Grupetto table**. There is no separate Fit details tab.
The table has **Table** and **Backups** tabs; guided capture has Ride and Review
views. The Table page groups **Guided capture**, **Manual editor**, **Capture settings**,
and **Clear table...** in compact horizontal controls, wrapping on narrow screens
while retaining 48 dp touch targets. Clear opens an explicit confirmation with
**Keep table** initially focused. Cancel, Back, or dismissal leaves everything intact.
Only **Clear Grupetto table** deletes saved Grupetto efforts and manual edits, stops
recording, and disables correction; exported backups and bike calibration are unchanged.
The table page compares **Raw bike error** and **Corrected error** as mean
absolute error in watts against the pedals, on exactly the same saved paired
efforts. Corrected error uses the actual active snapshot, not the newer draft. When correction
is off, it is labeled **preview** and uses the table Activate would select, or the
unfinished draft when no validated table exists. Unknown values show a dash.
This descriptive comparison updates with recording and activation. Both it and
the separate saved-effort RMSE are informational; neither gates table updates.
Capture → Review shows the Grupetto draft's measured cells, saved-effort error and
readiness, with a shortcut to its controls. The rider flow does not expose the
experimental sensor-board fit or write controls. Raw paired efforts remain saved
for future work translating Grupetto calibration into bike-table offsets.

**Save**, **Load**, and **Share** on the table screen create and restore JSON
backups. A backup includes the current draft, the last usable activation snapshot,
paired ride efforts, bike serial, power-meter address, service version, and baseline.
Save uses Android's file picker; Share attaches the same backup to the Android
share sheet. Pause capture before loading. Load validates the file and previews
its bike, meter, and effort counts before replacing the current Grupetto tables.
Correction remains off until Activate is selected, and normal bike identity and
baseline verification still applies. Loading never writes the bike's own table.
Draft and saved snapshot are stored together atomically; existing installations
read their older separate files until the first write migrates them.

Resuming Record merges compatible pending ride efforts by timestamp without duplicates.
After Clear, earlier efforts stay cleared. External meters may change between efforts:
their address is descriptive metadata and never disables Record or blocks a table update.
A meter switch restarts the current hold so one effort cannot mix two meters.
A different bike, firmware or board baseline cannot be mixed into a
draft. Empty capture files may adopt the current bike, meter and baseline because
they contain no observations; this leaves the separate Grupetto power table intact.
Captures containing efforts still require matching bike, firmware and baseline. A failure
names the mismatched fields and logs the cause under `CalibrationCapture`.
The private atomic snapshot is `grupetto-calibration.json`; earlier separate
draft and activation files are migrated on the next write.

## Fit and scope

### Manual power table editor

Pause **Record**, then select **Manual editor** to open **Power table studio**.
The editor needs at least one saved usable effort to establish the bike identity
and resistance baseline; a pedal connection is not needed while editing.
The editor uses a full-display immersive window, hiding the system bars while
open (swipe from an edge to reveal them temporarily). The heatmap covers all 31
baseline resistance points and 45–120 rpm. **Fit table** sizes cells to the actual
viewport in whole pixels and fits cadence labels within their rows; all four
corners remain visible without scrolling, including after leaving Zoom.
Tap a cell to inspect its raw resistance AD and enter a signed percentage of raw
bike watts (−35% to +35%). **Set correction** assigns the desired correction;
**−1% / +1%** nudge it by one percentage point. Choose a cell, cadence row,
resistance column, or the whole table. **Zoom in** adds numeric cell labels and
larger touch targets; the scrollable grid retains its axis labels.

Set and nudge change only the selected cells. There is no automatic neighbor
blending. Use the explicit **Smooth** or **Fill gaps** tools when desired. Manual
input uses the same correction bounds as recordings and shares their cell values;
it does not create synthetic pedal measurements.

Four one-touch quick fixes stay visible above the table. The selection inspector
uses **Edit**, **Scope**, and **Tools** pages without scrolling. Quick fixes affect
the whole table and remain reversible with Undo:

- **Fill all gaps:** fills every uncovered cell by inverse-distance interpolation
  of the eight closest covered cells, with distance scaled by the blend widths.
  It preserves existing covered values and extends nearby trends at the edges.
  Filled cells are estimates, not new measurements.
- **Smooth whole table:** blends every cell halfway toward its immediate
  neighbors' average to reduce abrupt changes.
- **Reduce all 20%:** scales every percentage correction toward zero.
- **Reset corrections:** sets the shared correction table to zero. Original
  pedal observations remain available for diagnostics; Undo restores the table
  before the reset until the edit session is saved.

The inspector also retains **Smooth selection** and **Reduce corrections 20%**
for cell, cadence-row, resistance-column, or whole-table selections. Compare mode
disables edits and quick fixes until **Show edited** is selected.
The editor explicitly uses the bundled Lato font for every text style, including
buttons, headings, and fields. Labels use printable ASCII; manual-cell dots are
drawn shapes, avoiding device-dependent symbol glyphs. The same bundled font is
the default for all app typography styles.
While the keyboard is open, the manual editor temporarily focuses on the numeric
input and Set correction action, keeping the typed value and controls visible.

**Undo / Redo** covers the last 100 changes, including tools and resets.
**Compare original** shows the surface from when the editor opened.
**Revert this editing session** restores that opening snapshot and is itself
undoable. Closing with unsaved edits offers Keep editing or Discard edits.
Changes affect riding only after **Save & apply** when already active, or after
**Save draft** followed by **Activate** when correction is off. A changed table
or resumed recording prevents a stale editor session from overwriting new data.

Manual tools and recordings now modify **one shared correction surface**. Saving
an edit updates calibration targets at all saved effort coordinates to the edited
surface. Original pedal readings are retained once as audit metadata, so the
Raw bike/Corrected error display still compares against actual measured pedals, not
edited targets. There is no additive manual offset layer to compound or reapply.
Coverage from editing remains descriptive and never counts as measured efforts.
Explicit manual saves (including Reset corrections) can make a sparse table usable.

A subsequent accepted recording averages into that same cell using its capped
recording weight. Manual changes preserve that weight (or seed an empty cell with
one value), so future recordings always remain able to change the edit. A new -10% observation after a manual -10% edit stays -10%, not -19%.
No new observations means no reconstruction: restarting, pausing/resuming, and
receiving duplicate old captures preserve the shared table exactly. A persisted
processed-through timestamp prevents old overlapping/imported observations from
replaying over newer edits. Capture timestamps use the device clock; readings at
or before that boundary are historical, not new calibration updates.

Schema-1 backups remain importable. Migration validates their offsets, preserves
the exact saved gains, folds existing manual adjustments into targets once, keeps
original readings, and discards the offsets. Draft and saved activation snapshots
migrate independently. Schema-3 profiles store the shared surface, processed timestamp,
and each cell's averaging weight (0–10). Schema-1 and schema-2 surfaces migrate
without erasing gains or recordings: every saved grid value becomes one initial
cell value, so a subsequent recording has half the weight until the cell reaches
ten values. This deliberately preserves legacy values rather than reconstructing
a different table from historical samples. Under direct-cell lookup fractional
coordinates now use the nearest grid cell, not the old interpolated surface.
Older app versions reject schema-3 profiles rather than misinterpreting them.
Valid old snapshots are rewritten atomically at startup, including the older
separate draft/active files. Versions v0.0.49 and v0.0.50 use the supported schema-1
format. If persisted calibration is malformed, oversized, or uses an unsupported
format, startup keeps a recoverable copy, writes an empty calibration snapshot,
and turns correction off. An on-disk reset marker prevents old captures from
reappearing after an interrupted upgrade. Unrelated settings and the separate raw
sensor-board capture remain unchanged. Real storage I/O failures do not authorize
discarding data; correction stays off with an error instead. Explicit backup
imports still reject invalid files without replacing the current calibration.

Export a backup before testing this branch if you may want to return to an older
version. CI tests the source-built v0.0.49/v0.0.50 debug APKs with the same disposable
key, then installs the new APK in place without clearing data. The old binary
writes the smoothed calibration used by the migration assertions; these tests do
not substitute for matching the production APK signature on the actual bike.

### Recorded fit

Each accepted stable recording belongs to exactly one cell of the 31 × 16 table.
Raw resistance AD maps through the bike's 31-point baseline and rounds to the
nearest column. Cadence rounds to the nearest 5-rpm row from 45–120 rpm. Half-cell
ties round up. No recording changes a neighboring cell.

The cell stores the arithmetic mean of its first ten correction multipliers
(pedal watts / raw bike watts). Every later accepted recording updates it as
`0.9 * previous multiplier + 0.1 * new multiplier`. Values are bounded to
0.65–1.35 before averaging and persisted as log multipliers for editor/file
compatibility. The averaging weight saturates at ten and survives restart and
backup import, even when older capture history is trimmed. The first recording
in an empty cell therefore matches pedal watts exactly within the safety limits.
Subsequent differing recordings intentionally average; the newest one does not
replace the previous nine.

During a ride, lookup uses the current rounded cell if populated; otherwise it
uses the closest populated cell. Distance is squared Euclidean distance in grid
steps: one resistance column and one 5-rpm row each count as one step. Equal
distances select lower resistance, then lower cadence. This is a deterministic
fallback, not interpolation, and can introduce a step when crossing between
cells. There is no fading to native power within the table domain. Outside the
baseline/cadence range, with no filled cells, or at zero power, power is unchanged.
Lookup directly returns a populated current cell; fallback uses one bounded,
allocation-free scan of at most 496 cells. Sensor callbacks perform no fitting or
deferred cache construction.

The current table cell pulses whenever fresh bike telemetry is available, even
with Record off. When lookup falls back to another populated cell, that source
cell has a pulsing green border. Fallback does not mark an empty current cell as
measured or fill it automatically.

Capture stability, settling and valid-data requirements are unchanged. One valid
recorded effort in one populated cell is enough to enable **Activate**; empty or
invalid-only data cannot activate correction. Empty cells use nearest-filled lookup,
so additional resistances and cadences improve coverage but do not gate activation.
First activation remains explicit, and older one-point drafts become eligible without
replaying recordings or changing saved gains. Display averages and rounded broadcasts can differ from the
instantaneous paired recording.

**Raw bike error** and **Corrected error** are mean absolute differences from the
original pedal measurements over saved efforts, using the current active table
(or the activation preview when off). They are descriptive retrospective errors,
not live power or a guarantee that a new recording will improve all old samples.
Manual edits, changing equipment behavior and conflicting recordings can make the
corrected value worse. Diagnostics also show saved-effort RMSE; no held-out Gaussian
validation score remains. Neither error metric gates activation or updates.

With Record + Activate on, every new saved effort updates the current surface and
publishes the latest usable table. Equal or worse error scores do not block it.
First activation stays explicit; Off is never silently enabled. Resuming Record
also applies the latest usable draft immediately. Error comparisons and cadence
regressions remain diagnostics, not acceptance rules or proof of accuracy.
Bike identity, service version and baseline must match. Each replacement is
saved atomically before publication; Off, Clear, or stopping recording during the
save cancels publication and restores the previous disk snapshot. A failed save
keeps the current active table. The controls show the active snapshot's effort count.

Application is limited to the verified Affernet 2.4.490 original-Bike parcel,
sensor-board power source, matching frame identity and baseline. Baseline checks
run every five seconds; a missing/stale check bypasses correction. A changed board
table or source disables it. Calibration capture always uses a separate raw
subscription, preventing corrected output from feeding its own training data.

## Rider guidance and background collection

The guided screen shows knob direction/position, 75/85-rpm targets, a configurable
countdown and three dots per setting. Completed targets offer untimed recovery
with a large Resume button centered in the recovery panel.
Three broad resistance bands at each cadence accept coarse SmartSpin2k shifts:
anywhere in green counts, with each band spanning two baseline intervals. Adjacent
bands do not overlap. These are riding coverage targets; fitting still uses the
exact measured AD and cadence, with its own validation requirements. Displayed
resistance uses the same current-resistance field as the main ride display.
**Easier** and **Harder** buttons shift all three suggested resistance bands down
or up, within the baseline endpoints. Each target shows approximate pedal watts
from nearby recorded efforts, with cadence scaling and local interpolation.
Unmeasured targets show **Learning W** until nearby data exists; these estimates
are display guidance only and never enter the calibration fit. Rest and Skip remain
available. Guided capture has a direct **Table view** button.
Recording from the table uses the same quality checks without targets. A dedicated
foreground service and wake lock keep capture alive without the ride overlay;
its notification opens the table. Closing a dialog does not pause it.

Both meters may read as low as 5 W, including easy spinning around 12–15 W.
Zero/below-5 W readings reset the window with an explicit low-power cue. Capture,
the Grupetto fit and saved-data loading share this power range.

**Capture settings** on the calibration table edits settle time (0–15 seconds) and
maximum resistance movement (1–100% of one local baseline interval, minimum three
raw AD units). Defaults are three seconds and 10%. Settings persist across restarts;
changing them restarts only the in-progress hold and keeps all saved efforts.
With defaults, eight distinct paired readings spanning seven elapsed seconds form
an effort; the first three seconds settle, leaving five readings. Every setting
still requires at least five settled readings. Cadence spread may reach 8 rpm.
Power can have one isolated outlier, but the remaining spread and the
first-to-last settled medians must stay within max(20 W, 12%) at normal power.
Below 40 W the absolute allowance scales down to half the median, with a 5 W floor,
so low-power ramps are not mistaken for steady efforts. Stale packets,
disconnects, sustained ramps and resistance transitions are rejected. Shorter
windows trade some delay rejection for achievable human efforts.

## Experimental sensor-board workflow

The currently hidden experimental board workflow requires a validated
board fit. It uses the known `FC index checksum F6` capture-current-position
command, never an arbitrary numeric-value command or a tablet override. The user
must stop pedaling and physically align the knob to each calculated raw-AD target.

Before writing, the workflow reads the complete board table twice, matches the
baseline, and atomically stores `bike-board-write-journal.json`. Each explicit
point tap rechecks the complete table, requires three stopped/exact-position
readings, journals the attempt, sends the command once, and reads the complete
table twice. A lost response never causes an automatic retry. Factory endpoints
are preserved. An interrupted/failed session keeps the backup and offers guided
restoration; it does not silently roll back by guessing knob positions.

SensorData 2.8.3016 is the supported transport. Affernet normally owns its serial
port, so setup can report blocked access. The app does not stop Affernet or
invalidate factory calibration. This path is fixture-tested but **has not written
to or been validated on the real board**. An aligned knob can still move between
the final read and the capture command; readback detects a mismatch and stops.
No board write occurs during recording, fitting, activation of the Grupetto table,
or installation. Board setup disables the separate Grupetto correction.

### Optional refit from retained original measurements

While recording is paused, select **Calculate New Solution** above the Corrected error watt difference.
This explicit offline operation uses all eligible retained efforts (at most 3600),
not only each cell's rolling ten-value live weight. It does not run automatically
between rides. The preview compares the current and fitted table's mean absolute
error, signed bias (corrected minus pedals), RMSE, fitted cells and bound hits.
**Apply refit** changes the shared table only after confirmation; **Cancel** leaves
it unchanged. **Undo refit** restores the complete previous saved snapshots while
no later calibration operation has changed them. Applying while active updates
live compensation; applying while inactive leaves correction off. Normal readiness
is preserved rather than bypassed.

The objective is bounded least absolute deviation in each already populated cell:
minimize `sum(abs(nativeWatts * gain - originalPedalWatts))`, with gain in
0.65–1.35. The optimum is the weighted median of original-pedal/native ratios,
weighted by native watts. When half the weight lies on either side of an interval,
the old gain is projected into that interval, preserving an already optimal value.
No global polynomial, neighbor smoothing, or fictitious zero-error claim is used.
Cells without eligible evidence retain their values; unfilled cells remain unfilled,
although their nearest-cell fallback output can change when its source is refitted.

Refit leaves original observations and edited targets untouched. It preserves the
processed-history boundary (advancing only to cover retained data it consumes)
and each cell's live count, raising it to the smaller of ten and its fit-evidence
count if larger. Subsequent live recording therefore still uses the same
first-ten average / tenth-weight update, not a permanent offline override.
Record, Clear, imports, edits, activation or baseline changes invalidate stale
previews. Fitting runs off the UI thread; disk save completes before publication.

#### Exported measurement fields

For backward compatibility, exported `pedalWatts` is the calibration target. Before
any manual target change it is the original pedal measurement. Once an edit
changes that target, `recordedPedalWatts` retains the immutable original measurement.
For example, native 195 W, target 191.69 W and recorded 177 W mean a 177 W pedal
observation with a later 191.69 W calibration target, not two simultaneous pedal
readings. Displayed errors and optional refit always use `recordedPedalWatts` when
present, otherwise `pedalWatts`. Live recording uses newly captured pedal readings.
