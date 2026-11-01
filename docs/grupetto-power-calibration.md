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
With recording off, the snapshot stays fixed; **Update active table** remains a
manual option. Turning Activate off prevents automatic activation.
Turning it off retains the last usable snapshot. If a later draft lacks coverage,
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
is paused. Fit messages and validation details are available under **Fit details**.
Calibration dialogs use wide, compact layouts without scrolling. The table has
**Table**, **Fit details**, and **Backups** tabs; guided capture has Ride and Review
views. Secondary actions use compact buttons while riding actions keep larger targets.
The table page compares **Before calibration** and **After calibration** as mean
absolute error in watts against the pedals, on exactly the same saved paired
efforts. After uses the actual active snapshot, not the newer draft. When correction
is off, it is labeled **preview** and uses the table Activate would select, or the
unfinished draft when no validated table exists. Unknown values show a dash.
This descriptive comparison updates with recording and activation. Both it and
the separate held-out RMSE are informational; neither gates table updates.
Capture → Review shows the Grupetto draft's measured cells, validation error and
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

**Blend into neighbors** is on by default. It tapers the change in log-power space
using the recorded fitter's Gaussian length scales (2.5 resistance points and
15 rpm), while hitting the selected correction exactly. Turn blending off to
change only selected cells. Manual input uses the same ride-time interpolation
and correction bounds as recorded calibration, without creating synthetic pedal
efforts or claiming measured confidence.

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
- **Restore recorded fit:** removes all manual edits, including edits saved in
  earlier sessions, and restores the fit from the recorded efforts.

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

Manual adjustments are stored as a separate additive log-gain layer in the
atomic snapshot and JSON backups. Restarts and subsequent recorded fits retain
that layer; a new fit may change the underlying recorded correction. Coverage
from manual editing is displayed but never counted as measured ride coverage.
Manual edits can make a sparse draft usable by explicit activation. Restoring
an insufficient recorded fit disables correction until more coverage is collected.
Before/After error uses the edited surface; held-out validation still describes
the underlying recorded fit and is labeled accordingly.

### Recorded fit

The model fits log(pedal watts / native watts) as a function of raw AD, mapped to
the 31-point baseline coordinate, and cadence. A zero-mean Gaussian kernel has
length scales of 2.5 baseline intervals and 15 rpm. Cholesky solves the regularized
system. Local repeated efforts are aggregated; observation noise includes a
minimum floor and robust within-cell scatter. All usable efforts enter the fit and
validation. The matrix is bounded to 128 cells by widening the local resistance
and cadence bins when necessary; cells are never dropped by subsampling. This
preserves isolated low-cadence measurements that the previous 64-cell cap omitted.

The posterior mean is multiplied by local support and bounded to 0.65–1.35 times
native power. A 31 × 16 table covers the full baseline and 45–120 rpm, with bilinear
interpolation during the ride. Correction fades toward native power away from
measurements; this is not evidence of accuracy at unmeasured high powers. Outside
the baseline/cadence range, native power is used. Zero power stays zero.

Validation leaves an entire local cell out, including all repeats, and remains
available as an informational score. Activation requires six efforts in six cells,
three resistance locations, cadences spanning at least 10 rpm, and finite fit
values. Error improvement is no longer required, including for the first table.
Saved drafts are re-evaluated on load under these coverage requirements.

With Record + Activate on, every saved effort refits the accumulated data and
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
