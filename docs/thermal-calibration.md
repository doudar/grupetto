# Experimental temperature-aware calibration

The flywheel estimate is now recorded alongside each usable streamed pair, and **new temperature-tagged
cells** learn a gain at a fixed reference temperature. Existing unknown-temperature cells keep their
previous behavior. This is an experimental correction based on an unvalidated physical hypothesis,
not a measured Peloton compensation curve. Displayed pedal watts and stored original watts stay raw.

## One input/output contract

Let `N` be native bike watts, `P` original pedal watts, and `T` the saved estimated temperature.
The fixed reference is **65°F (18.333…°C)**; changing room temperature does not change this reference.

`F(T) = clamp(1 / (1 + 0.004 * (T - Tref)), 0.85, 1.10)`

`gain_ref = P / (N * F(Tsample))`

`output = Nlive * clamp(gain_ref(cell) * F(Tlive), 0.5, 1.5)`

At a sample's own temperature this reproduces its pedal watts, unless the final safety limit saturates.
Multiplying both input powers by F before taking their ratio would cancel the correction; the code
instead divides only the fitting target by F. The existing cadence normalization still transforms both
powers to the cell cadence. Temperature is applied exactly once at live lookup and never fed back to
the heat model, which continues to use uncorrected native watts.

The coefficient 0.004/K is a **provisional low-speed inverse-resistivity approximation**, not a fitted
constant for this bike, all cadences, magnets or brake geometries. The roughly 9% change across 25°C
is a consequence of that hypothesis, not validation. No magnet-temperature or drivetrain-loss factors
are stacked on it. The factor bounds limit this experiment independently of the existing ±50% final
correction limit. Reference gains may exceed ±50%: their useful range is [0.5/1.10, 1.5/0.85], because
values outside it produce the same saturated output for every permitted F. This avoids clipping a
valid warm correction just because its reference gain exceeds 1.5. Manual input, nudges and sliders
use the selected cells' reference range (rounded inward to one decimal); a mixed legacy/thermal
selection uses their common range. This does not widen the final applied power limit.

## Fitting, edits and missing data

For absolute-watt-error fitting, the effective input is `x = N * F` and the target is P. Both the live
weighted median and Calculate New Solution weight normalized ratios by x, including the cadence
factor. Native-only weights would change the objective. This optimizes the unsaturated objective;
final output limits can leave residual error at saturation. Outlier tests compare reference-normalized
ratios, preserving sustained-shift admission and isolated-glitch rejection.

Each cell stores whether its gain is temperature-normalized plus its most recent training factor as
a fallback anchor. On the first new tagged observation, only known-temperature observations fit that
cell; old unknown samples remain audit evidence within the existing retention limits. Unknown new
observations cannot overwrite a normalized cell. Other cells and nearest-filled lookup are unchanged.

During a transient missing estimate, live output holds its last applied estimate; at startup with no
such history, a normalized cell uses its saved anchor, never blindly F=1. Capture does not reuse that
held value: it accepts only a snapshot at or before the native packet, at most 1500 ms old. A bounded
24-entry history, sampled at most every 250 ms, supports delayed capture polling without assigning
a future estimate to a past sample.

Error summaries and refit metrics replay each sample's saved temperature. Unknown-temperature samples
are excluded if their lookup now uses a normalized cell, so raw and corrected errors use the same
eligible subset. Manual edits retain immutable original pedal watts and temperature; editable targets
are mapped at each sample's temperature. Old observations cannot immediately undo an edit. Thermal
cell percentages in the editor/chart are reference gains at 65°F. Explicit smoothing never averages
legacy and thermal bases together; Fill gaps uses neighbors with the nearest source's basis. Reset
corrections clears thermal-cell modes as well as gains, returning identity output. Undo retains both.

## Storage, provenance and reversibility

Backup envelope schema 3 contains profile schema 5, model `thermal_cell_stream_v5`; raw capture schema
is 3. Each paired-second tuple retains its original first eleven fields and appends a temperature tuple
or null: `[estimatedCelsius, roomFahrenheit, elapsedTimestamp, heatModelVersion, historyUnobserved,
confidenceOrigin]`. No normalized watts overwrite original bike or pedal measurements. Manual edits
continue to retain original pedal measurements in `recordedPedalWatts`.

Heat-model version 1 identifies C=8000 J/K and H=8 W/K. Version 2 records the aluminum-disc node
of the coupled disc/hub estimator described in [flywheel temperature](flywheel-temperature.md).
New samples use version 2. Both versions are accepted, serialized and replayed using their original
saved temperatures; old samples, reference gains, thermal anchors and the 65 F reference are not
rewritten. The inverse-resistivity correction itself is unchanged, so its model identifier is unchanged.
Old app builds that only accept estimator version 1 cannot import samples recorded by version 2;
keep a pre-update export if rollback is needed. Confidence distinguishes `AmbientStartAssumed`
from `InterruptedHistory`; neither claims a measured or known-cold start. Missing intervals retain the
estimator's explicitly uncertain zero-input cooling assumption. Sample provenance is immutable after
capture. Changing heat-model parameters cannot reconstruct past temperatures from this snapshot alone;
full native-power history would be needed. Changing the thermal coefficient requires a new correction
model identifier and explicit reconstruction of reference gains from original observations, never
reinterpretation of an existing normalized surface. Unknown model identifiers are rejected.

Schema 1–4 profiles and older raw captures upgrade without inventing temperatures or changing saved
gains. Before rewriting an old local file, an fsynced `before-thermal-v5-*` recovery copy is required.
Exports include temperatures and thermal anchors. Old app builds cannot read this new format; rollback
must use the retained pre-upgrade file/export, not the new file. Existing settings and sensor pairing
are unaffected. The 100 accepted seconds per cell, atomic/batched persistence, and 32 MB import bound
remain in force; compact temperature tuples avoid per-sample JSON maps at full capacity.

## Validation

Tests cover self-reproduction, equal reference gains across cold/warm samples, exact-once application,
alpha-zero identity, correct thermal weights, expanded reference/final limits, nearest lookup, legacy
cells, missing estimates, normalized outliers, saved-temperature errors, manual edit/reset behavior,
confidence gaps, migration/recovery copies, serialization and rejection of invalid provenance/models.
These validate implementation consistency; measured ride validation is still required to establish
whether the provisional coefficient and temperature estimate improve this bike's power accuracy.
