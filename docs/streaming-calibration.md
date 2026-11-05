# Streaming tablet calibration (schema 4)

Recording now uses paired observations instead of overlapping five-reading effort medians.
The sensor-board candidate/write workflow remains separate and does not learn from raw stream seconds.

## Admission and settling

The fitting cell still uses the existing nearest 5-rpm row. The capture gate instead
holds cadence within an adjustable window about its settling anchor: **±5 rpm by default**.
Crossing a storage-row boundary inside that window does not restart settling. Each
accepted observation keeps its exact AD/RPM and updates only its actual fitting cell.

Settle time defaults to two seconds (0–15 configurable). Raw AD must stay within the
selected percentage of one local baseline interval (default 2%, configurable 1–100%,
minimum 2 AD). A rounded displayed resistance-level change alone does not reset a hold.
Raw resistance or cadence leaving their windows resets settling and names that reason
in the collection status. A separate More forgiving preset selects ±5 rpm, 10% resistance
window and a 6-percentage-point ratio tolerance; it is applied only when explicitly saved.

Accept at most one distinct pair per 1,000 ms. Both native and pedal timestamps must advance.
Bike readings must be at most 1,000 ms old and pedals at most 1,500 ms. Future/invalid readings,
disconnects or a polling gap over 1,500 ms reset the gate. Freshness, valid-power/source,
ratio bounds and duplicate-packet checks cannot be disabled. The gate polls every 250 ms;
polling is not evidence. Changing settings restarts only the in-progress hold and keeps
accepted observations. All stream settings persist separately from legacy board settings.

## Isolated glitches and sustained changes

Compare measured pedal/native ratio with the median of the last five accepted ratios in that cell,
using tolerance max(configured percentage-point floor, configured multiplier × median
absolute deviation). Defaults remain 0.03 and 4×; controls allow 1–30 percentage points and 1–10×. This compares the recent stream,
never the old fitted gain. An isolated deviation is quarantined, not stored as accepted evidence.
The configured 2–10 consecutive deviating pairs (default 3), within the configured
percentage-point floor of their pending median, establish
a new level. Preserve all confirmed original pairs and timestamps, marking the first as the new level's
boundary. An inconsistent reading, return to the recent level, or interrupted stable gate discards
the unfinished quarantine. Nearby cell crossings keep separate confirmation queues;
more than five seconds without a fresh pair for a cell expires that pending queue.
The first valid observation remains eligible for explicit activation;
zero or invalid observations never enable activation.

When isolated-outlier rejection is switched off, valid distinct pairs are admitted without
quarantine; robust fitting and all hard validity checks still apply. Disabling rejection
does not force immediate replacement of the retained 100-second estimate.

Per-cell estimates use a native-watt-weighted median of pedal/native multipliers, minimizing absolute
watt error like Calculate New Solution. Only the most recent established level in the retained bucket
contributes; earlier evidence stays auditable until it rolls out. Equal-half optima retain the old
multiplier if it lies in the optimal interval. No neighbor value is read or modified by recording.
Empty-cell runtime lookup still uses the nearest populated cell. This does not compensate flywheel
inertia, meter timing or systematic sensor error; the stable gate avoids transitions instead.

## Retention, manual edits and storage

Each cell holds the latest 100 accepted one-second observations, accumulated across visits. Time
away from a cell never expires its bucket and gaps do not earn seconds. At most 49,600 stream
observations plus 3,600 original legacy effort aggregates are retained. Raw native/pedal times,
AD, cadence, native power and original measured pedal power are kept; duration is exactly one
accepted second, not an invented reconstruction of a longer effort.

Manual edits and recordings share one table. Editing rebases the editable pedal targets while
recordedPedalWatts retains the original evidence. The saved recordedThrough watermark prevents
unchanged old observations from immediately undoing an edit. New observations may improve that
same cell; there is no permanent override layer. Explicit Calculate New Solution uses original
measurements and can replace manual values, as its confirmation explains.

Memory is bounded per cell. Admission/retention/fit work runs away from UI callbacks; the capture
loop is not a binder callback. Atomic snapshots are flushed every five seconds, after the first
accepted observation, and on pause, stale/disconnected streams or capture shutdown. Pause flushes
before disabling the table consumer. An abrupt process kill can lose at most the unfinished batch;
a restart never resumes recording automatically. JSON reads are bounded to 32 MB; compact export
encoding keeps the bounded dual-profile snapshot within that limit.

## Upgrade and compatibility

Tablet profile schema 4, model stable_cell_stream_v4, labels each sample as paired_second or
legacy_effort_aggregate. Raw capture schema 2 carries the same observation provenance. The outer
backup wrapper remains schema 2; draft/saved profiles carry their own explicit schema 4.

Schema 1-3 tablet gains, editable targets, immutable originals and manual coverage remain intact.
Old effort medians remain one aggregate each and contribute zero stream seconds. No effort is
expanded into 100 invented readings. Before replacing an older on-disk format, a fsynced uniquely
named .before-stream-v4-* recovery copy preserves the original bytes. Unchanged legacy gains are
not replayed at startup; the first new stream in a cell estimates that cell from real stream data.
Unrelated settings and sensor pairings are unchanged. Exports/imports validate provenance, kinds,
bucket bounds, baseline and identity compatibility. Older builds cannot read schema 4: rollback
requires an older-format export/recovery copy rather than feeding the new file to an old build.

## Display

The collection bucket directly below the chart legend, above the error summary, shows the current cell's accepted seconds out of 100 and the actual
waiting, settling, collecting, outlier-checking or paused state. Fill has a finite eased transition;
cell changes crossfade the bucket, with no blended calibration gains or neighboring updates.
Android animation-duration scale controls these finite animations. Accessibility reports the cell,
cadence, exact count and state. Current/fallback cell pulses remain distinct from evidence count.

## CI fixture and capacity validation

API29's default AOSP image can omit heapgrowthlimit, leaving ART at a 16 MiB growth
limit despite a Pixel2/2GiB profile. The unchanged full 49,600-observation dual-profile
test fails at that limit and passes at 256 MiB. The disposable test fixture explicitly
sets a tablet-sized 256 MiB heap and verifies it; APK production heap flags are unchanged.
This does not establish full-capacity support for a 16 MiB application heap. Stream tuples
avoid per-observation maps during serialization and keep the worst-case export bounded.
CI enables/verifies KVM when the hosted runner exposes it, and logs an explicit
software fallback when /dev/kvm is absent. Guest Wi-Fi
is disabled to avoid the observed API29 system_server Wi-Fi/telephony null-list crash;
sensor fixtures and tests do not depend on guest Wi-Fi. Setup failures preserve crash
diagnostics and their original exit status. The preparation helper rejects physical
targets before mutation and is covered by mocked safety/failure tests.

## Calculation-only cadence normalization

For known 15-character bike identities on Affernet 2.4.490, use the extracted Rexon/Tonic
factory reference selected by PowerFactory's serial convention (R-prefix selects Rexon).
The source APK hash is 4bbc926f581e6132ad3b92cec0718920a8c3f5ab3670295b013276165117690f.
These reference curves describe cadence shape; they are not a claim that factory watts
or a particular bike's board readings are perfectly accurate. Unknown identities or invalid
reference inputs retain the original calculation inputs rather than guessing a model.

For an observation at raw AD x and actual cadence r, let c be its fitting row's center:

    factor = referenceWatts(x, c) / referenceWatts(x, r)
    calibrationPedals = originalPedalTarget * factor
    calibrationNative = originalNativeWatts * factor

Thus 97 rpm in the 95-rpm row scales pedal power down to its 95-rpm equivalent; 92.5 rpm
scales up to 95. Native comparison uses the same reference cadence, avoiding a false
correction caused by comparing 95-rpm pedal watts with 97-rpm native watts. Both live
fitting and Calculate New Solution consume these derived pairs. Displayed PEDALS,
recorded raw measurements/timestamps and immutable originals remain unchanged. No
normalization is applied to the live meter stream, recording file evidence or ERG feedback.

Scaling both readings preserves their ratio. The practical fitting change is consistent
cell-center weighting in the absolute-watt objective, so a slightly faster sample does not
receive extra weight solely because it produced more watts. A single pair retains the same
gain when applied back at its actual RPM. This is not a new correction offset, neighboring
cell blend, substitute for stable capture, or compensation for flywheel inertia/time alignment.
Percent-based outlier ratios are also invariant to this common scaling; wider capture
windows and configurable rejection thresholds address the separate admission problem.

The arrays in PelotonCadenceReference preserve the extracted factory constants. Rexon
uses its vendor AD-to-internal-row integer mapping; Tonic uses its fractional Lxx mapping.
Cadence interpolation is continuous within the reference breakpoints to handle fractional
RPM. Reference baseline validity is cached so fitting does not allocate validation lists
for every observation. Tests cover factory anchors, 97→95, 92.5→95, unchanged raw/display
inputs, same-RPM gain correctness, weighting, refits, settings persistence and UI placement.
