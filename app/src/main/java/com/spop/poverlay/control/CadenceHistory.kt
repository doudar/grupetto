package com.spop.poverlay.control

import kotlin.math.abs

/**
 * Recent cadence, used to pick the resistance level for a lower ERG target.
 *
 * Riders often spin faster during hard efforts (about 97 rpm at 360 W against 90 rpm in
 * recovery on the rides this was tuned from) and settle back within a few seconds of the
 * drop. Choosing the recovery level from the cadence at the moment of the drop picked a
 * level one too easy, and power undershot 15-30 W. The cadence the rider has held at
 * about the new target, or failing that their typical cadence, predicts it much better.
 * Climbs keep the current cadence: riders have usually already spun up by then.
 */
class CadenceHistory {
    private data class Entry(val time: Long, val cadence: Float, val watts: Int)

    private val all = ArrayDeque<Entry>()
    private val steady = ArrayDeque<Entry>()

    /** Any pedalling sample, kept at most once per second for the typical-cadence median. */
    fun record(time: Long, cadence: Float) {
        if (cadence < MIN_CADENCE || !cadence.isFinite()) return
        add(all, Entry(time, cadence, 0), time)
    }

    /** A sample taken while ERG was settled on [watts]. */
    fun recordSteady(time: Long, cadence: Float, watts: Int) {
        if (cadence < MIN_CADENCE || !cadence.isFinite()) return
        add(steady, Entry(time, cadence, watts), time)
    }

    /** Cadence held at about [watts] (within 10%) in the last ten minutes, or null without enough of it. */
    fun cadenceAt(watts: Int, now: Long): Float? {
        prune(now)
        val near = steady.filter { abs(it.watts - watts) <= watts * NEAR_FRACTION }.map { it.cadence }
        return if (near.size >= MIN_STEADY_SAMPLES) median(near) else null
    }

    /** Median cadence over the last two minutes, or null without enough of it. */
    fun typicalCadence(now: Long): Float? {
        prune(now)
        val recent = all.filter { now - it.time <= TYPICAL_WINDOW_MS }.map { it.cadence }
        return if (recent.size >= MIN_TYPICAL_SAMPLES) median(recent) else null
    }

    fun clear() { all.clear(); steady.clear() }

    private fun add(queue: ArrayDeque<Entry>, entry: Entry, now: Long) {
        val last = queue.lastOrNull()
        if (last != null && entry.time - last.time < SAMPLE_INTERVAL_MS) return
        queue.addLast(entry)
        prune(now)
    }

    private fun prune(now: Long) {
        while (all.isNotEmpty() && now - all.first().time > MEMORY_MS) all.removeFirst()
        while (steady.isNotEmpty() && now - steady.first().time > MEMORY_MS) steady.removeFirst()
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    companion object {
        private const val MIN_CADENCE = 50f
        private const val SAMPLE_INTERVAL_MS = 1000L
        private const val MEMORY_MS = 600_000L
        private const val TYPICAL_WINDOW_MS = 120_000L
        private const val NEAR_FRACTION = .1f
        private const val MIN_STEADY_SAMPLES = 15
        private const val MIN_TYPICAL_SAMPLES = 50
    }
}
