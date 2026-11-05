package com.spop.poverlay.calibration

import kotlin.math.abs
import kotlin.math.max

/** A real, distinct paired observation. Legacy effort medians have no observation metadata. */
data class CalibrationObservation(val nativeAt: Long, val pedalAt: Long,
    val durationMillis: Int = 1000, val startsNewLevel: Boolean = false)

internal const val CalibrationCellSeconds = 100
internal const val MaxCalibrationSamples = 3600 + 496 * CalibrationCellSeconds

/** Accepted seconds belong to a cell, not to a global wall-clock window. Legacy effort evidence
 * is retained separately, once per original effort, and never expanded into invented samples. */
internal fun retainCalibrationObservations(baseline: List<Int>, samples: List<CalibrationSample>): List<CalibrationSample> {
    val legacy = samples.filter { it.observation == null }.distinctBy { it.capturedAt }.takeLast(3600)
    val stream = samples.filter { it.observation != null }.groupBy { calibrationCellIndex(baseline, it.ad, it.rpm) }
        .filterKeys { it != null }.values.flatMap { group -> group.distinctBy {
            Triple(it.capturedAt, it.observation!!.nativeAt, it.observation.pedalAt)
        }.sortedBy { s -> s.capturedAt }.takeLast(CalibrationCellSeconds) }
    return (legacy + stream).sortedBy { it.capturedAt }
}

/** Stable-gated stream, independent of the fitted gain. The first real point can activate.
 * Subsequent ratio deviations > max(0.03, 4 * recent MAD) are quarantined. Three consecutive
 * ratios within 0.03 establish a new level, retaining their original measurements/timestamps.
 * This rejects isolated glitches without permanently anchoring learning to an old correction.
 */
internal class CalibrationStream(private val baseline: List<Int>, history: List<CalibrationSample> = emptyList()) {
    private val recent = history.filter { it.observation != null }.groupBy {
        calibrationCellIndex(baseline, it.ad, it.rpm)
    }.mapValues { (_, values) -> values.takeLast(5).map { it.measuredPedalWatts / it.nativeWatts }.toMutableList() }.toMutableMap()
    private val pending = mutableListOf<CalibrationSample>()
    private var cell: Int? = null
    private var anchorAd = 0
    private var resistance: Int? = null
    private var meter: String? = null
    private var stableSince = 0L
    private var lastNative = Long.MIN_VALUE
    private var lastPedal = Long.MIN_VALUE
    private var lastAcceptedAt = Long.MIN_VALUE
    private var lastPollAt: Long? = null
    var progress = CaptureProgress(streaming = true, totalSeconds = 2, settleSeconds = 2)
        private set

    fun reset(phase: CapturePhase = CapturePhase.Waiting) {
        cell = null; meter = null; pending.clear(); lastPollAt = null
        progress = CaptureProgress(phase, totalSeconds = 2, settleSeconds = 2, streaming = true)
        // Packet high-water marks survive a pause/reset: a cached packet is not new evidence.
    }

    fun add(frame: CalibrationFrame, pedal: Int, pedalAt: Long, address: String,
        now: Long, capturedAt: Long): List<CalibrationSample> {
        if (lastPollAt?.let { now - it !in 0..1500 } == true) reset()
        lastPollAt = now
        val index = calibrationCellIndex(baseline, frame.ad.toDouble(), frame.rpm)
        val sample = CalibrationSample(frame.ad.toDouble(), frame.rpm, frame.nativeWatts,
            pedal.toDouble(), capturedAt, observation = CalibrationObservation(frame.timestamp, pedalAt))
        if (frame.powerSource != 1 || index == null || !sample.usableForCalibration(baseline) ||
            now - frame.timestamp !in 0..1000 || now - pedalAt !in 0..1500 || address.isBlank()) {
            reset(if (frame.nativeWatts < MinimumCalibrationWatts || pedal < MinimumCalibrationWatts)
                CapturePhase.LowPower else CapturePhase.Waiting)
            return emptyList()
        }
        val interval = (index / 16).coerceAtMost(29)
        val tolerance = max(2.0, (baseline[interval + 1] - baseline[interval]) * .02)
        if (cell != index || meter != address || resistance != frame.resistance || abs(frame.ad - anchorAd) > tolerance) {
            val phase = if (cell != null && cell!! % 16 != index % 16) CapturePhase.CadenceChanged else CapturePhase.Settling
            cell = index; meter = address; anchorAd = frame.ad; resistance = frame.resistance
            stableSince = now; pending.clear()
            progress = CaptureProgress(phase, totalSeconds = 2, settleSeconds = 2, streaming = true, cell = index)
        }
        val elapsed = now - stableSince
        if (elapsed < 0) { reset(); return emptyList() }
        if (elapsed < 2000) {
            progress = progress.copy(phase = CapturePhase.Settling, seconds = (elapsed / 1000).toInt())
            return emptyList()
        }
        if (frame.timestamp <= lastNative || pedalAt <= lastPedal ||
            lastAcceptedAt != Long.MIN_VALUE && now - lastAcceptedAt < 1000) return emptyList()
        lastNative = frame.timestamp; lastPedal = pedalAt; lastAcceptedAt = now
        val ratios = recent.getOrPut(index) { mutableListOf() }
        val ratio = sample.measuredPedalWatts / sample.nativeWatts
        val center = ratios.takeIf { it.isNotEmpty() }?.let(::median)
        val threshold = center?.let { max(.03, 4 * median(ratios.map { r -> abs(r - it) })) } ?: .03
        val accepted = if (center == null || abs(ratio - center) <= threshold) {
            pending.clear(); listOf(sample)
        } else {
            if (pending.isNotEmpty() && abs(ratio - median(pending.map { it.measuredPedalWatts / it.nativeWatts })) > .03) pending.clear()
            pending += sample
            if (pending.size < 3) {
                progress = progress.copy(phase = CapturePhase.Outlier, seconds = 2)
                return emptyList()
            }
            ratios.clear()
            pending.mapIndexed { i, s -> if (i == 0) s.copy(observation = s.observation!!.copy(startsNewLevel = true)) else s }
                .also { pending.clear() }
        }
        ratios += accepted.map { it.measuredPedalWatts / it.nativeWatts }
        while (ratios.size > 5) ratios.removeAt(0)
        progress = progress.copy(phase = CapturePhase.Collecting, seconds = 2)
        return accepted
    }
}

/** Weighted median minimizes absolute watt error (same objective as Calculate New Solution).
 * Only the latest established level contributes; no neighboring cell is read or changed. */
internal fun streamCellGain(samples: List<CalibrationSample>, old: Double): Double {
    val start = samples.indexOfLast { it.observation?.startsNewLevel == true }.coerceAtLeast(0)
    val values = samples.drop(start).sortedBy { it.pedalWatts / it.nativeWatts }
    val half = values.sumOf { it.nativeWatts } / 2
    var cumulative = 0.0
    for ((i, sample) in values.withIndex()) {
        cumulative += sample.nativeWatts
        if (cumulative >= half) {
            val lower = sample.pedalWatts / sample.nativeWatts
            val upper = if (cumulative == half && i < values.lastIndex) values[i + 1].pedalWatts / values[i + 1].nativeWatts else lower
            return old.coerceIn(lower, upper).coerceIn(.65, 1.35)
        }
    }
    return old
}
