package com.spop.poverlay.calibration

import kotlin.math.*

enum class CalibrationMode { Guided, FreeRide }

data class CalibrationTarget(val point: Int, val ad: Int, val rpm: Int, val repeats: Int,
    val lowerAd: Int, val upperAd: Int, val approximateWatts: Int? = null) {
    val complete get() = repeats >= 3
    // Adjacent bands share a boundary, but an effort belongs to only one band.
    fun containsResistance(value: Double) = value >= lowerAd && value < upperAd
    fun containsCadence(value: Double) = (value / 5).roundToInt() * 5 == rpm
    /** The green band occupies -0.5..0.5 on the display, even with uneven table spacing. */
    fun markerPosition(value: Double): Float {
        val halfWidth = if (value < ad) ad - lowerAd else upperAd - ad
        return ((value - ad) / halfWidth * .5).toFloat().coerceIn(-1f, 1f)
    }
}

/** Broad riding targets, not fitting cells. Samples retain their exact AD and cadence. */
internal class CalibrationGuide(private val baseline: List<Int>, startAd: Int) {
    private var first = (baseline.indices.minBy { abs(baseline[it] - startAd) } - 2).coerceIn(1, 25)
    private val skipped = mutableSetOf<Pair<Int, Int>>()
    var selected: Pair<Int, Int>? = null
    fun easier() { first = (first - 2).coerceAtLeast(1); skipped.clear(); selected = null }
    fun harder() { first = (first + 2).coerceAtMost(25); skipped.clear(); selected = null }
    fun skip(target: CalibrationTarget?) { target?.let { skipped += it.point to it.rpm }; selected = null }
    fun cells(samples: List<CalibrationSample>): List<CalibrationTarget> = listOf(75, 85).flatMap { rpm ->
        (first..first + 4 step 2).map { index ->
            val target = CalibrationTarget(index, baseline[index], rpm, 0, baseline[index - 1], baseline[index + 1])
            target.copy(repeats = samples.count { target.containsResistance(it.ad) && target.containsCadence(it.rpm) },
                approximateWatts = estimateTargetWatts(baseline, target, samples))
        }
    }
    fun target(cells: List<CalibrationTarget>): CalibrationTarget? {
        selected?.let { choice -> cells.firstOrNull { it.point to it.rpm == choice && !it.complete }?.let { return it } }
        return cells.firstOrNull { !it.complete && it.point to it.rpm !in skipped }
    }
}

/** Display estimate only: interpolate nearby measured pedal torque, scaled to target cadence.
 * Never feed estimates into fitting or invent watt targets outside measured resistance coverage.
 */
internal fun estimateTargetWatts(baseline: List<Int>, target: CalibrationTarget, samples: List<CalibrationSample>): Int? {
    val nearby = samples.filter { abs(it.rpm - target.rpm) <= 20 && it.rpm > 0 && it.pedalWatts in CalibrationPowerRange }
        .mapNotNull { sample -> calibrationCoordinate(baseline, sample.ad)?.let { x ->
            if (abs(x - target.point) <= 4) x to (sample.pedalWatts * target.rpm / sample.rpm) else null
        } }
    if (nearby.isEmpty()) return null
    val points = nearby.groupBy { (it.first * 2).roundToInt() }.values.map {
        median(it.map { value -> value.first }) to median(it.map { value -> value.second })
    }.sortedBy { it.first }
    val x = target.point.toDouble()
    val lower = points.lastOrNull { it.first <= x }
    val upper = points.firstOrNull { it.first >= x }
    val estimate = if (lower != null && upper != null && upper.first - lower.first <= 4) {
        if (upper.first == lower.first) lower.second
        else lower.second + (upper.second - lower.second) * (x - lower.first) / (upper.first - lower.first)
    } else points.minByOrNull { abs(it.first - x) }?.takeIf { abs(it.first - x) <= 1 }?.second
    return estimate?.takeIf { it.isFinite() && it in CalibrationPowerRange }?.roundToInt()
}
