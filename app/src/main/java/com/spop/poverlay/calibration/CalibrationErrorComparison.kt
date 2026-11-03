package com.spop.poverlay.calibration

import kotlin.math.abs

/** Descriptive mean absolute error on original paired saved efforts, not live or held-out accuracy. */
data class CalibrationErrorComparison(val beforeWatts: Double, val afterWatts: Double?,
    val count: Int, val preview: Boolean)

internal fun calibrationErrorComparison(draft: GrupettoCalibrationProfile?, active: GrupettoCalibrationProfile?,
    saved: GrupettoCalibrationProfile?): CalibrationErrorComparison? {
    val capture = draft?.capture ?: active?.capture ?: saved?.capture ?: return null
    // Show the table actually in use. When off, preview what Activate would select, or the unfinished draft.
    val profile = if (active != null) active else activationProfile(draft, saved) ?: draft
    val table = profile?.takeIf { it.matches(capture) }?.table?.takeIf { it.baseline == capture.baseline }
    val samples = capture.samples.filter { it.nativeWatts in CalibrationPowerRange &&
        it.measuredPedalWatts in CalibrationPowerRange && it.rpm in 45.0..120.0 &&
        calibrationCoordinate(capture.baseline, it.ad) != null }
    if (samples.isEmpty()) return null
    val before = samples.sumOf { abs(it.nativeWatts - it.measuredPedalWatts) } / samples.size
    val after = table?.let { correction ->
        (samples.sumOf { abs(correction.corrected(it.nativeWatts, it.ad, it.rpm) - it.measuredPedalWatts) } / samples.size)
            .takeIf { it.isFinite() }
    }
    return CalibrationErrorComparison(before, after, samples.size, preview = active == null)
}
