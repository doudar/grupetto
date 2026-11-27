# Grupetto contributor instructions

## CI and release workflows

- External fork pull requests must run their builds and tests in the fork. Do not approve or trigger upstream build jobs for a fork PR.
- When reviewing a fork PR, check for changes to `.github/workflows/`, local actions, and scripts or Gradle configuration that affect how upstream CI builds, signs, or publishes the app. Ask the contributor to remove changes to upstream CI behavior. A maintainer can propose the intended CI change separately from a branch in `doudar/grupetto`.
- Keep production signing credentials and release permissions out of jobs that execute fork code. Publish official APKs only from a trusted upstream build. Never promote a fork-provided artifact to an official release.

## Code review

- **Overlay redraws.** Trace a sensor update to the composable that displays it. A change in watts should update the power readout without redrawing the whole overlay or calibration chart. Check for frame loops, animations that continue while hidden, and timers that redraw a paused display. Update a whole-second timer only when the displayed second changes.
- **CPU and battery.** Check how often work runs during a ride and while the bike is idle. Reuse sensor events and existing subscriptions. Look for duplicate polling, repeated scans, per-sample logging, and calibration calculations that rescan the entire ride history on each reading.
- **Memory over a long ride.** Check whether buffers, queues, caches, or pending coroutines grow with every sample. Keep display history bounded and stream recordings that require every sample. Look for large list copies, bitmap allocations, or path construction inside drawing and sensor callbacks.
- **Lifecycle and I/O.** Verify that opening, minimizing, and reopening the overlay or reconnecting a trainer leaves only one listener or job per intended consumer. Release resources when their owner stops. Keep file writes and serialization off the UI thread. Preserve the sample order and timing required by recording and bike control when reducing UI updates.
- **Reuse existing methods.** Look for an existing implementation and inspect its callers before adding a helper, calculation, or transport path. Adapt it when it can serve both uses and preserve caller behavior. If a separate method is necessary, explain the difference in its contract, such as timing, units, or ownership.
- **Verify performance findings.** Identify the triggering event, its frequency, and the work it repeats. For a suspected regression, compare the same ride or sensor replay before and after the change. Choose a measure that tests the concern, such as redraw count with the overlay minimized, CPU time during recording, or retained heap after reconnects.
