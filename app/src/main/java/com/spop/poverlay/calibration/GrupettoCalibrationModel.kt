package com.spop.poverlay.calibration

import kotlin.math.*

/** Descriptive errors on the same saved efforts, not held-out validation or an activation gate. */
data class ActiveCalibrationComparison(val activeRmse: Double, val candidateRmse: Double,
    val count: Int, val cadenceRegressions: Int, val appliedRmse: Double = candidateRmse) {
    val improved get() = count >= 6 && activeRmse.isFinite() && candidateRmse.isFinite() && appliedRmse.isFinite() &&
        candidateRmse >= 0 && appliedRmse >= 0 && max(candidateRmse, appliedRmse) < activeRmse
}

/** One shared correction per resistance/cadence cell. Gains are stored as log multipliers for
 * file/editor compatibility, but recordings are averaged in multiplier (not logarithmic) space.
 */
data class GrupettoCalibrationTable(val baseline: List<Int>, val gains: List<Double>, val confidence: List<Double>,
    val sampleCount: Int, val cells: Int, val beforeRmse: Double, val afterRmse: Double, val ready: Boolean,
    val activeComparison: ActiveCalibrationComparison? = null,
    val correctionPercent: List<Double?> = emptyList(),
    val recordedThrough: Long = Long.MIN_VALUE,
    val manualCoverage: Set<Int> = emptySet(),
    val recordedCoverage: Set<Int> = emptySet(),
    // Capped averaging weight, not an audit count. Zero means this cell has no correction yet.
    val cellSampleCounts: List<Int> = emptyList()) {
    /** Empty counts only occur in legacy/programmatic tables. Their saved surface is a seed,
     * never a large invented recording count. Newly fitted tables always carry explicit counts. */
    internal fun effectiveCellSampleCounts(): List<Int> = if (cellSampleCounts.size == 496) cellSampleCounts
        else List(496) { 1 }

    /** Direct-cell lookup needs one populated cell, not the former coverage threshold.
     * Do not trust an old serialized ready bit to enable an empty or malformed table.
     */
    val canActivate get() = ready && hasUsableCells

    internal val hasUsableCells get() = sampleCount > 0 && cells in 1..496 && validCalibrationTable(baseline) &&
        gains.size == 496 && gains.all { it.isFinite() && it in ln(.65)..ln(1.35) } &&
        confidence.size == 496 && confidence.all { it.isFinite() && it in 0.0..1.0 } &&
        (cellSampleCounts.isEmpty() || cellSampleCounts.size == 496 && cellSampleCounts.all { it in 0..10 }) &&
        effectiveCellSampleCounts().any { it > 0 }

    fun currentCellIndex(ad: Double, rpm: Double): Int? = calibrationCellIndex(baseline, ad, rpm)

    // Distance is squared Euclidean grid distance: one resistance column and one 5-rpm
    // row each count as one step. Scan at most 496 values, with no allocations or deferred
    // cache construction in the sensor callback. Ties keep the first (lowest) flat index.
    fun lookupCellIndex(ad: Double, rpm: Double): Int? {
        val cell = currentCellIndex(ad, rpm) ?: return null
        if (cellSampleCounts.size != 496 || cellSampleCounts[cell] > 0) return cell
        var closest = -1
        var shortest = Int.MAX_VALUE
        for (source in 0 until 496) {
            if (cellSampleCounts[source] == 0) continue
            val dx = source / 16 - cell / 16
            val dy = source % 16 - cell % 16
            val distance = dx * dx + dy * dy
            if (distance < shortest) { shortest = distance; closest = source }
        }
        return closest.takeIf { it >= 0 }
    }

    fun corrected(watts: Double, ad: Double, rpm: Double): Double {
        if (!watts.isFinite() || watts <= 0) return watts
        val cell = lookupCellIndex(ad, rpm) ?: return watts
        return watts * exp(gains[cell].coerceIn(ln(.65), ln(1.35)))
    }
}

internal fun calibrationCoordinate(table: List<Int>, ad: Double): Double? {
    if (!ad.isFinite() || table.size != 31 || ad < table.first() || ad > table.last()) return null
    val i = table.indexOfLast { it <= ad }.coerceIn(0, 29)
    return i + (ad - table[i]) / (table[i + 1] - table[i])
}

internal fun calibrationCellIndex(baseline: List<Int>, ad: Double, rpm: Double): Int? {
    if (rpm !in 45.0..120.0) return null
    val x = calibrationCoordinate(baseline, ad) ?: return null
    return x.roundToInt().coerceIn(0, 30) * 16 + ((rpm - 45) / 5).roundToInt().coerceIn(0, 15)
}

object GrupettoCalibrationFitter {
    fun fit(baseline: List<Int>, samples: List<CalibrationSample>, active: GrupettoCalibrationTable? = null,
        seed: GrupettoCalibrationTable? = null): GrupettoCalibrationTable? {
        require(validCalibrationTable(baseline))
        val usable = samples.filter { it.usableForCalibration(baseline) }
        if (usable.isEmpty()) return null
        val previous = seed?.takeIf { it.baseline == baseline }
        val gains = previous?.gains?.toMutableList() ?: MutableList(496) { 0.0 }
        val counts = previous?.effectiveCellSampleCounts()?.toMutableList() ?: MutableList(496) { 0 }
        usable.filter { previous == null || it.capturedAt > previous.recordedThrough }
            .sortedBy { it.capturedAt }.forEach { sample ->
                val cell = calibrationCellIndex(baseline, sample.ad, sample.rpm)!!
                val measurement = (sample.pedalWatts / sample.nativeWatts).coerceIn(.65, 1.35)
                val count = counts[cell]
                val nextCount = (count + 1).coerceAtMost(10)
                val mean = if (count == 0) measurement
                    else exp(gains[cell]) + (measurement - exp(gains[cell])) / nextCount
                gains[cell] = ln(mean.coerceIn(.65, 1.35))
                counts[cell] = nextCount
            }
        val ready = counts.any { it > 0 }
        var result = GrupettoCalibrationTable(baseline, gains, counts.map { it / 10.0 }, usable.size,
            counts.count { it > 0 }, 0.0, 0.0, ready,
            recordedThrough = max(previous?.recordedThrough ?: Long.MIN_VALUE, usable.maxOf { it.capturedAt }),
            manualCoverage = previous?.manualCoverage ?: emptySet(), cellSampleCounts = counts)
        val before = sqrt(usable.map { (it.nativeWatts - it.measuredPedalWatts).pow(2) }.average())
        val after = sqrt(usable.map { (result.corrected(it.nativeWatts, it.ad, it.rpm) - it.measuredPedalWatts).pow(2) }.average())
        val comparison = active?.takeIf { it.baseline == baseline }?.let { incumbent ->
            fun error(table: GrupettoCalibrationTable, sample: CalibrationSample) =
                (table.corrected(sample.nativeWatts, sample.ad, sample.rpm) - sample.measuredPedalWatts).pow(2)
            val regressions = usable.groupBy { (it.rpm / 5).roundToInt() }.values.count { band ->
                val old = sqrt(band.map { error(incumbent, it) }.average())
                sqrt(band.map { error(result, it) }.average()) - old > max(.5, old * .05)
            }
            ActiveCalibrationComparison(sqrt(usable.map { error(incumbent, it) }.average()), after,
                usable.size, regressions, after)
        }
        result = result.copy(beforeRmse = before, afterRmse = after, activeComparison = comparison)
        return result.withCalibrationCoverage(usable)
    }
}
