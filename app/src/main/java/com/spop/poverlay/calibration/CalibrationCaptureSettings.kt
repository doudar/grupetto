package com.spop.poverlay.calibration

/** Keep five distinct settled readings regardless of the configurable meter-lag period. */
data class CalibrationCaptureSettings(val settleSeconds: Int = 3, val resistanceMovementPercent: Int = 10) {
    init {
        require(settleSeconds in 0..15)
        require(resistanceMovementPercent in 1..100)
    }
    val totalSeconds get() = settleSeconds + 5
    fun resistanceTolerance(interval: Int) = kotlin.math.max(3.0, interval * resistanceMovementPercent / 100.0)
}
