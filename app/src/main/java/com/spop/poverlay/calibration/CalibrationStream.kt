package com.spop.poverlay.calibration

import kotlin.math.abs
import kotlin.math.max

/** A real, distinct paired observation. Legacy effort medians have no observation metadata. */
data class CalibrationObservation(val nativeAt: Long, val pedalAt: Long,
    val durationMillis: Int = 1000, val startsNewLevel: Boolean = false)

internal const val CalibrationCellSeconds = 100
internal const val MaxCalibrationSamples = 3600 + 496 * CalibrationCellSeconds

/** Ephemeral receipt from retention, never a timer or a saved calibration value. */
data class CalibrationRollingReplacement(val cell: Int, val at: Long, val count: Int)

internal fun calibrationRollingReplacement(baseline: List<Int>, before: List<CalibrationSample>,
    retained: List<CalibrationSample>, cell: Int?, now: Long): CalibrationRollingReplacement? {
    if (cell == null) return null
    fun key(s: CalibrationSample) = Triple(s.capturedAt, s.observation!!.nativeAt, s.observation.pedalAt)
    val current = retained.filter { it.observation != null && calibrationCellIndex(baseline, it.ad, it.rpm) == cell }
    if (current.size < CalibrationCellSeconds) return null
    val keys = current.map(::key).toHashSet()
    val removed = before.filter { it.observation != null && calibrationCellIndex(baseline, it.ad, it.rpm) == cell }
        .distinctBy(::key).count { key(it) !in keys }
    return if (removed > 0) CalibrationRollingReplacement(cell, now, removed) else null
}

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

/** Adjustable stable gate; admission windows never change the actual sample's fitting cell.
 * Outlier comparisons use recent measured ratios, not the old fitted table gain. */
internal class CalibrationStream(private val baseline: List<Int>, history: List<CalibrationSample> = emptyList(),
    settings: CalibrationStreamSettings = CalibrationStreamSettings()) {
    var settings = settings
        private set
    private val historicalGroups = history.filter { it.observation != null }.groupBy {
        calibrationCellIndex(baseline, it.ad, it.rpm)
    }
    private val recentThermal = historicalGroups.mapValues { (_, values) -> values.any(CalibrationThermalCorrection::usable) }.toMutableMap()
    private val recent = historicalGroups.mapValues { (cell, values) ->
        values.filter { CalibrationThermalCorrection.usable(it) == recentThermal[cell] }.takeLast(5)
            .map(CalibrationThermalCorrection::ratio).toMutableList()
    }.toMutableMap()
    private val pendingByCell = mutableMapOf<Int, MutableList<CalibrationSample>>()
    private val pendingLastAt = mutableMapOf<Int, Long>()
    private var cell: Int? = null
    private var anchorAd = 0
    private var anchorRpm = 0.0
    private var meter: String? = null
    private var stableSince = 0L
    private var settleReason: String? = null
    private var lastNative = Long.MIN_VALUE
    private var lastPedal = Long.MIN_VALUE
    private var lastAcceptedAt = Long.MIN_VALUE
    private var lastPollAt: Long? = null
    private val latestRecordedPair = history.filter { it.observation != null }.maxByOrNull { it.capturedAt }
    private var historicalMarksSeeded = false
    private fun progress(phase: CapturePhase = CapturePhase.Waiting) = CaptureProgress(phase,
        totalSeconds = settings.settleSeconds, settleSeconds = settings.settleSeconds, streaming = true)
    var progress = progress()
        private set

    fun updateSettings(value: CalibrationStreamSettings) {
        if (settings == value) return
        settings = value
        reset()
    }
    fun reset(phase: CapturePhase = CapturePhase.Waiting) {
        cell = null; meter = null; pendingByCell.clear(); pendingLastAt.clear(); lastPollAt = null; settleReason = null
        progress = progress(phase)
        // Keep packet high-water marks and recent per-cell evidence through settings/pause resets.
    }

    fun add(frame: CalibrationFrame, pedal: Int, pedalAt: Long, address: String,
        now: Long, capturedAt: Long, temperature: CalibrationTemperature? = null): List<CalibrationSample> {
        if (lastPollAt?.let { now - it !in 0..1500 } == true) reset()
        lastPollAt = now
        val index = calibrationCellIndex(baseline, frame.ad.toDouble(), frame.rpm)
        val sample = CalibrationSample(frame.ad.toDouble(), frame.rpm, frame.nativeWatts,
            pedal.toDouble(), capturedAt, observation = CalibrationObservation(frame.timestamp, pedalAt),
            temperature = temperature?.takeIf { it.valid && frame.timestamp - it.estimatedAt in 0..1500 })
        if (frame.powerSource != 1 || index == null || !sample.usableForCalibration(baseline) ||
            now - frame.timestamp !in 0..1000 || now - pedalAt !in 0..1500 || address.isBlank()) {
            reset(if (frame.nativeWatts < MinimumCalibrationWatts || pedal < MinimumCalibrationWatts)
                CapturePhase.LowPower else CapturePhase.Waiting)
            return emptyList()
        }
        if (!historicalMarksSeeded) {
            historicalMarksSeeded = true
            // A fast Record off/on with zero settling must not count the last cached pair again.
            // Older visits/boots are not packet high-water marks for this live connection.
            latestRecordedPair?.takeIf { capturedAt - it.capturedAt in 0..5000 }?.observation?.let {
                lastNative = it.nativeAt; lastPedal = it.pedalAt
            }
        }
        val anchorInterval = calibrationCoordinate(baseline, anchorAd.toDouble())?.toInt()?.coerceIn(0, 29) ?: 0
        val tolerance = max(2.0, (baseline[anchorInterval + 1] - baseline[anchorInterval]) * settings.resistanceWindowPercent / 100.0)
        val cadenceChanged = cell != null && abs(frame.rpm - anchorRpm) > settings.cadenceWindowRpm
        val resistanceChanged = cell != null && abs(frame.ad - anchorAd) > tolerance
        if (cell == null || meter != address || cadenceChanged || resistanceChanged) {
            settleReason = when {
                cadenceChanged -> "Cadence outside window"
                resistanceChanged -> "Resistance outside window"
                else -> null
            }
            meter = address; anchorAd = frame.ad; anchorRpm = frame.rpm
            stableSince = now; pendingByCell.clear(); pendingLastAt.clear()
            progress = progress(CapturePhase.Settling)
        } // A row crossing within the hold keeps separate recent confirmations for each cell.
        cell = index
        progress = progress.copy(cell = index)
        val elapsed = now - stableSince
        if (elapsed < 0) { reset(); return emptyList() }
        if (elapsed < settings.settleSeconds * 1000L) {
            progress = progress.copy(phase = CapturePhase.Settling, seconds = (elapsed / 1000).toInt(), detail = settleReason)
            return emptyList()
        }
        if (frame.timestamp <= lastNative || pedalAt <= lastPedal ||
            lastAcceptedAt != Long.MIN_VALUE && now - lastAcceptedAt < 1000) return emptyList()
        lastNative = frame.timestamp; lastPedal = pedalAt; lastAcceptedAt = now
        val ratios = recent.getOrPut(index) { mutableListOf() }
        val pending = pendingByCell.getOrPut(index) { mutableListOf() }
        val thermal = CalibrationThermalCorrection.usable(sample)
        if (recentThermal[index] != thermal) {
            ratios.clear(); pending.clear(); recentThermal[index] = thermal
        }
        if (pendingLastAt[index]?.let { now - it > 5000 } == true) pending.clear()
        pendingLastAt[index] = now
        val ratio = CalibrationThermalCorrection.ratio(sample)
        val floor = settings.outlierTolerancePercent / 100.0
        val center = ratios.takeIf { it.isNotEmpty() }?.let(::median)
        val threshold = center?.let { max(floor, settings.outlierNoiseMultiplier * median(ratios.map { r -> abs(r - it) })) } ?: floor
        val accepted = if (!settings.outlierRejection || center == null || abs(ratio - center) <= threshold + 1e-9) {
            pending.clear(); listOf(sample)
        } else {
            if (pending.isNotEmpty() && abs(ratio - median(pending.map(CalibrationThermalCorrection::ratio))) > floor + 1e-9) pending.clear()
            pending += sample
            if (pending.size < settings.newLevelReadings) {
                progress = progress.copy(phase = CapturePhase.Outlier, seconds = settings.settleSeconds,
                    detail = "Checking shifted ratio: ${pending.size}/${settings.newLevelReadings}")
                return emptyList()
            }
            ratios.clear()
            pending.mapIndexed { i, s -> if (i == 0) s.copy(observation = s.observation!!.copy(startsNewLevel = true)) else s }
                .also { pending.clear() }
        }
        ratios += accepted.map(CalibrationThermalCorrection::ratio)
        while (ratios.size > 5) ratios.removeAt(0)
        progress = progress.copy(phase = CapturePhase.Collecting, seconds = settings.settleSeconds, detail = null)
        return accepted
    }
}

/** Weighted median minimizes absolute watt error (same objective as Calculate New Solution).
 * Only the latest established level contributes; no neighboring cell is read or changed. */
internal fun streamCellGain(samples: List<CalibrationSample>, old: Double,
    cadenceReference: PelotonCadenceReference? = null, thermal: Boolean = false): Double {
    val start = samples.indexOfLast { it.observation?.startsNewLevel == true }.coerceAtLeast(0)
    val values = samples.drop(start).map { calibrationCalculationPair(it, cadenceReference, thermal = thermal) }
        .sortedBy { it.pedalWatts / it.nativeWatts }
    val half = values.sumOf { it.nativeWatts * it.thermalFactor } / 2
    var cumulative = 0.0
    for ((i, sample) in values.withIndex()) {
        cumulative += sample.nativeWatts * sample.thermalFactor
        val atHalf = abs(cumulative - half) <= max(1e-9, half * 1e-12)
        if (cumulative >= half || atHalf) {
            val lower = sample.pedalWatts / sample.nativeWatts
            val upper = if (atHalf && i < values.lastIndex) values[i + 1].pedalWatts / values[i + 1].nativeWatts else lower
            return old.coerceIn(lower, upper).coerceIn(if (thermal) CalibrationThermalCorrection.referenceRange
                else CalibrationCorrectionLimits.minimumMultiplier..CalibrationCorrectionLimits.maximumMultiplier)
        }
    }
    return old
}
