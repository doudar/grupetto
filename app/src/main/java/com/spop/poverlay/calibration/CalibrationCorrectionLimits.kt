package com.spop.poverlay.calibration

import kotlin.math.ln

/** Tablet calibration only; independent of sensor-board writes and resistance control. */
internal object CalibrationCorrectionLimits {
    const val minimumMultiplier = .5
    const val maximumMultiplier = 1.5
    const val maximumPercent = 50.0
    val logRange = ln(minimumMultiplier)..ln(maximumMultiplier)
}
