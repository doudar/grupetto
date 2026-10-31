package com.spop.poverlay.calibration

import kotlin.math.*

/** Both errors use the same efforts. The candidate predicts each cell with that cell held out;
 * the incumbent uses the exact table currently applied on the bike. Never compare historical RMSEs.
 */
data class ActiveCalibrationComparison(val activeRmse: Double, val candidateRmse: Double,
    val count: Int, val cadenceRegressions: Int, val appliedRmse: Double = candidateRmse) {
    // Diagnostic only. Error comparisons no longer gate activation or automatic updates.
    val improved get() = count >= 6 && activeRmse.isFinite() && candidateRmse.isFinite() && appliedRmse.isFinite() &&
        candidateRmse >= 0 && appliedRmse >= 0 && max(candidateRmse, appliedRmse) < activeRmse
}

/** A smooth, regularized log-power correction over raw resistance and cadence.
 * Zero-mean Gaussian prior returns to native power away from observations.
 * The saved 31 x 16 surface is bilinearly interpolated during riding (no solver in the callback).
 */
data class GrupettoCalibrationTable(val baseline: List<Int>, val gains: List<Double>, val confidence: List<Double>,
    val sampleCount: Int, val cells: Int, val beforeRmse: Double, val afterRmse: Double, val ready: Boolean,
    // Ephemeral: recomputed against the current active snapshot on every fit, never restored from disk.
    val activeComparison: ActiveCalibrationComparison? = null,
    // Display-only correction percentages, with observed coverage rebuilt on load.
    val correctionPercent: List<Double?> = emptyList(),
    // Separate from ride observations: additive log-gain edits survive subsequent fits.
    val manualOffsets: List<Double> = emptyList(),
    val manualCoverage: Set<Int> = emptySet(),
    // Display-only, rebuilt from real efforts, never treated as measured data by the fitter.
    val recordedCoverage: Set<Int> = emptySet()) {
    fun corrected(watts: Double, ad: Double, rpm: Double): Double {
        if (!watts.isFinite() || watts <= 0 || !ad.isFinite() || rpm !in 45.0..120.0) return watts
        val x = calibrationCoordinate(baseline, ad) ?: return watts
        val y = (rpm - 45) / 5
        val i = floor(x).toInt().coerceIn(0, 29)
        val j = floor(y).toInt().coerceIn(0, 14)
        fun at(a: Int, b: Int) = gains[a * 16 + b]
        val low = at(i, j) * (1 - (x - i)) + at(i + 1, j) * (x - i)
        val high = at(i, j + 1) * (1 - (x - i)) + at(i + 1, j + 1) * (x - i)
        val logGain = (low * (1 - (y - j)) + high * (y - j)).coerceIn(ln(.65), ln(1.35))
        return watts * exp(logGain)
    }
}

internal fun calibrationCoordinate(table: List<Int>, ad: Double): Double? {
    if (!ad.isFinite() || table.size != 31 || ad < table.first() || ad > table.last()) return null
    val i = table.indexOfLast { it <= ad }.coerceIn(0, 29)
    return i + (ad - table[i]) / (table[i + 1] - table[i])
}

object GrupettoCalibrationFitter {
    private data class Node(val x: Double, val rpm: Double, val value: Double,
        val noise: Double, val samples: List<CalibrationSample>)
    private fun kernel(x: Double, rpm: Double, b: Node): Double =
        exp(-.5 * (((x - b.x) / 2.5).pow(2) + ((rpm - b.rpm) / 15).pow(2)))

    fun fit(baseline: List<Int>, samples: List<CalibrationSample>, active: GrupettoCalibrationTable? = null): GrupettoCalibrationTable? {
        require(validCalibrationTable(baseline))
        val incumbent = active?.takeIf { it.baseline == baseline }
        val usable = samples.filter { calibrationCoordinate(baseline, it.ad) != null && it.rpm in 45.0..120.0 &&
            it.nativeWatts in CalibrationPowerRange && it.pedalWatts in CalibrationPowerRange && it.pedalWatts / it.nativeWatts in .5..2.0 }
        if (usable.isEmpty()) return null
        // Keep every effort. Subsampling sorted cells discarded entire measured regions, including
        // isolated low-cadence efforts. Coarsen local bins only when needed to bound the solver.
        var cellWidth = 1.0
        fun grouped() = usable.groupBy { (calibrationCoordinate(baseline, it.ad)!! * 2 / cellWidth).roundToInt() to
            ((it.rpm - 45) / (5 * cellWidth)).roundToInt() }
        var groups = grouped()
        while (groups.size > 128) { cellWidth *= 2; groups = grouped() }
        val nodes = groups.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (_, entries) ->
            val ratios = entries.map { ln(it.pedalWatts / it.nativeWatts) }
            val value = median(ratios)
            val scatter = median(ratios.map { abs(it - value) })
            Node(median(entries.map { calibrationCoordinate(baseline, it.ad)!! }), median(entries.map { it.rpm }), value,
                .16 / sqrt(entries.size.coerceAtMost(4).toDouble()) + (scatter / .25).pow(2), entries)
        }
        val n = nodes.size
        // Cholesky solves a strictly positive definite kernel + observation-noise system.
        val lower = Array(n) { DoubleArray(n) }
        for (i in 0 until n) for (j in 0..i) {
            var sum = kernel(nodes[i].x, nodes[i].rpm, nodes[j]) + if (i == j) nodes[i].noise else 0.0
            for (k in 0 until j) sum -= lower[i][k] * lower[j][k]
            lower[i][j] = if (i == j) sqrt(sum.coerceAtLeast(1e-9)) else sum / lower[j][j]
        }
        fun solve(rhs: DoubleArray): DoubleArray {
            val out = rhs.copyOf()
            for (i in 0 until n) { for (j in 0 until i) out[i] -= lower[i][j] * out[j]; out[i] /= lower[i][i] }
            for (i in n - 1 downTo 0) { for (j in i + 1 until n) out[i] -= lower[j][i] * out[j]; out[i] /= lower[i][i] }
            return out
        }
        val alpha = solve(DoubleArray(n) { nodes[it].value })
        fun bounded(mean: Double, support: Double) = (mean * support).coerceIn(ln(.65), ln(1.35))
        val gains = ArrayList<Double>(496)
        val confidence = ArrayList<Double>(496)
        for (x in 0..30) for (rpm in 45..120 step 5) {
            val k = DoubleArray(n) { kernel(x.toDouble(), rpm.toDouble(), nodes[it]) }
            val solved = solve(k)
            val support = k.indices.sumOf { k[it] * solved[it] }.coerceIn(0.0, 1.0)
            gains += bounded(k.indices.sumOf { k[it] * alpha[it] }, support)
            confidence += support
        }
        // Leave an entire local cell out, including its repeats; no training/validation leakage.
        val candidateTable = GrupettoCalibrationTable(baseline, gains, confidence, usable.size, n, 0.0, 0.0, false)
        var before = 0.0
        var after = 0.0
        var incumbentError = 0.0
        var appliedError = 0.0
        var count = 0
        val cadenceErrors = mutableMapOf<Int, DoubleArray>()
        nodes.forEachIndexed { i, node ->
            val inverseDiagonal = solve(DoubleArray(n) { if (it == i) 1.0 else 0.0 })[i]
            val mean = node.value - alpha[i] / inverseDiagonal
            val support = (1 - (1 / inverseDiagonal - node.noise)).coerceIn(0.0, 1.0)
            val gain = exp(bounded(mean, support))
            node.samples.forEach {
                before += (it.nativeWatts - it.pedalWatts).pow(2)
                val candidateError = (it.nativeWatts * gain - it.pedalWatts).pow(2)
                after += candidateError
                incumbent?.let { current ->
                    val activeError = (current.corrected(it.nativeWatts, it.ad, it.rpm) - it.pedalWatts).pow(2)
                    val tableError = (candidateTable.corrected(it.nativeWatts, it.ad, it.rpm) - it.pedalWatts).pow(2)
                    incumbentError += activeError
                    appliedError += tableError
                    val band = cadenceErrors.getOrPut((it.rpm / 5).roundToInt()) { DoubleArray(4) }
                    band[0] += activeError; band[1] += candidateError; band[2]++; band[3] += tableError
                }
                count++
            }
        }
        val old = sqrt(before / count)
        val new = sqrt(after / count)
        val ready = count >= 6 && n >= 6 && nodes.map { it.x.roundToInt() }.distinct().size >= 3 &&
            nodes.maxOf { it.rpm } - nodes.minOf { it.rpm } >= 10 && old.isFinite() && new.isFinite() &&
            gains.all { it.isFinite() } && confidence.all { it.isFinite() }
        val comparison = incumbent?.let {
            val regressions = cadenceErrors.values.count { band ->
                val previous = sqrt(band[0] / band[2])
                sqrt(max(band[1], band[3]) / band[2]) - previous > max(.5, previous * .05)
            }
            ActiveCalibrationComparison(sqrt(incumbentError / count), new, count, regressions, sqrt(appliedError / count))
        }
        val result = GrupettoCalibrationTable(baseline, gains, confidence, usable.size, n, old, new, ready, comparison)
        return result.withCalibrationCoverage(usable)
    }
}
