package com.spop.poverlay.calibration

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/** Descriptive errors on original paired efforts. Signed error is corrected minus pedal watts. */
data class CalibrationRefitMetrics(val meanAbsoluteError: Double, val meanSignedError: Double,
    val rmse: Double, val count: Int)

data class CalibrationRefitResult(val table: GrupettoCalibrationTable,
    val before: CalibrationRefitMetrics, val after: CalibrationRefitMetrics,
    val cellsAtBounds: Int, val fittedCells: Int)

/** The error display and optional refit use identical original-evidence eligibility.
 * Deliberately no live-fitter ratio gate: even a large disagreement is diagnostic evidence.
 */
internal fun eligibleCalibrationErrorSamples(baseline: List<Int>, samples: List<CalibrationSample>): List<CalibrationSample> =
    samples.filter { it.nativeWatts in CalibrationPowerRange && it.measuredPedalWatts in CalibrationPowerRange &&
        it.rpm in 45.0..120.0 && calibrationCoordinate(baseline, it.ad) != null }

/** Minimize sum |native * gain - measured pedals| independently in each occupied cell.
 * A weighted median of measured/native with native-watt weights minimizes this objective.
 * An exact-half split defines an interval of equally good solutions; retain the old multiplier
 * when possible, otherwise choose its nearest endpoint. No observations or neighbors are edited.
 */
internal fun refitCalibrationTable(table: GrupettoCalibrationTable,
    samples: List<CalibrationSample>): CalibrationRefitResult? {
    val eligible = eligibleCalibrationErrorSamples(table.baseline, samples)
    if (eligible.isEmpty()) return null
    val counts = table.effectiveCellSampleCounts().toMutableList()
    val groups = eligible.groupBy { calibrationCellIndex(table.baseline, it.ad, it.rpm)!! }
        .filterKeys { counts[it] > 0 }
    if (groups.isEmpty()) return null
    val gains = table.gains.toMutableList()
    var boundHits = 0
    groups.forEach { (cell, efforts) ->
        val ordered = efforts.sortedBy { it.measuredPedalWatts / it.nativeWatts }
        val half = ordered.sumOf { it.nativeWatts } / 2
        var cumulative = 0.0
        var lower = ordered.last().measuredPedalWatts / ordered.last().nativeWatts
        var upper = lower
        for ((index, effort) in ordered.withIndex()) {
            cumulative += effort.nativeWatts
            if (cumulative >= half) {
                lower = effort.measuredPedalWatts / effort.nativeWatts
                upper = if (cumulative == half && index < ordered.lastIndex)
                    ordered[index + 1].measuredPedalWatts / ordered[index + 1].nativeWatts else lower
                break
            }
        }
        val old = exp(gains[cell])
        val fitted = old.coerceIn(lower, upper).coerceIn(.65, 1.35)
        // Preserve exact stored bits for an unchanged optimum, including repeat previews.
        if (old != fitted) gains[cell] = ln(fitted)
        if (fitted == .65 || fitted == 1.35) boundHits++
        counts[cell] = maxOf(counts[cell], efforts.size.coerceAtMost(10))
    }
    val candidate = table.copy(gains = gains, cellSampleCounts = counts,
        confidence = counts.map { it / 10.0 }, cells = counts.count { it > 0 }, activeComparison = null)
        .withCalibrationCoverage(samples)
    fun metrics(surface: GrupettoCalibrationTable): CalibrationRefitMetrics {
        val errors = eligible.map { surface.corrected(it.nativeWatts, it.ad, it.rpm) - it.measuredPedalWatts }
        return CalibrationRefitMetrics(errors.map { abs(it) }.average(), errors.average(),
            sqrt(errors.map { it * it }.average()), errors.size)
    }
    val before = metrics(table)
    val after = metrics(candidate)
    val rawRmse = sqrt(eligible.map {
        val error = it.nativeWatts - it.measuredPedalWatts
        error * error
    }.average())
    return CalibrationRefitResult(candidate.copy(beforeRmse = rawRmse, afterRmse = after.rmse),
        before, after, boundHits, groups.size)
}
