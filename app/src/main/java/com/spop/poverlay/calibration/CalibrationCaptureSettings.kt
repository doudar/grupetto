package com.spop.poverlay.calibration

/** Legacy board-candidate window only. Tablet streams use the fixed two-second stable gate. */
data class CalibrationCaptureSettings(val settleSeconds: Int = 3, val resistanceMovementPercent: Int = 10) {
    init {
        require(settleSeconds in 0..15)
        require(resistanceMovementPercent in 1..100)
    }
    val totalSeconds get() = settleSeconds + 5
    fun resistanceTolerance(interval: Int) = kotlin.math.max(3.0, interval * resistanceMovementPercent / 100.0)
}
