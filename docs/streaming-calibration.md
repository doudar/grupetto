# Streaming tablet calibration (schema 4)

Recording now uses paired observations instead of overlapping five-reading effort medians.
The sensor-board candidate/write workflow remains separate and does not learn from raw stream seconds.

## Admission and settling

The current resistance/cadence cell uses the existing nearest 5-rpm band. After two seconds in that
band, with unchanged displayed resistance and raw AD within max(2 AD, 2% of one baseline step)
of its settling anchor, accept at most one distinct pair per 1,000 ms. Both native and pedal packet
timestamps must advance. Bike readings must be at most 1,000 ms old and pedals at most 1,500 ms;
future timestamps, invalid power/source/identity, disconnects, a polling gap over 1,500 ms, a cadence
band change, or knob movement reset settling. Polling occurs every 250 ms; it does not create samples.
Power remains bounded by the existing valid-reading and +/-35% correction safety checks.

## Isolated glitches and sustained changes

Compare measured pedal/native ratio with the median of the last five accepted ratios in that cell,
using tolerance max(0.03, four times median absolute deviation). This compares the recent stream,
never the old fitted gain. An isolated deviation is quarantined, not stored as accepted evidence.
Three consecutive deviating pairs whose ratios remain within 0.03 of their pending median establish
a new level. Preserve all three original pairs and timestamps, marking the first as the new level's
boundary. An inconsistent reading, return to the recent level, or interrupted stable gate discards
the unfinished quarantine. The first valid observation remains eligible for explicit activation;
zero or invalid observations never enable activation.

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

The bottom collection bucket shows the current cell's accepted seconds out of 100 and the actual
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
