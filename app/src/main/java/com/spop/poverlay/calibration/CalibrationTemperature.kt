package com.spop.poverlay.calibration

/** Immutable estimate associated with the original native packet, not a measured temperature.
 * Room and estimator version retain provenance if the estimate model changes in a later build. */
data class CalibrationTemperature(val celsius: Double, val roomFahrenheit: Double,
    val estimatedAt: Long, val estimatorVersion: Int = 1, val historyUnobserved: Boolean = true,
    val confidence: TemperatureConfidence = TemperatureConfidence.AmbientStartAssumed) {
    val valid get() = celsius.isFinite() && celsius in -100.0..500.0 &&
        validRoomTemperature(roomFahrenheit) && estimatedAt >= 0 && estimatorVersion == 1
}

/** Experimental low-speed inverse-resistivity approximation, NOT a fitted Peloton coefficient.
 * Fixed 65 F reference is independent of user room temperature. Do not stack magnet/loss guesses.
 * The thermal factor is bounded separately; final output retains the existing +/-50% limit.
 */
internal object CalibrationThermalCorrection {
    const val Model = "inverse_resistivity_004_ref65f_v1"
    const val ReferenceCelsius = (65.0 - 32.0) / 1.8
    const val CoefficientPerCelsius = .004
    val referenceRange = (CalibrationCorrectionLimits.minimumMultiplier / 1.10)..(CalibrationCorrectionLimits.maximumMultiplier / .85)
    val referenceLogRange = kotlin.math.ln(referenceRange.start)..kotlin.math.ln(referenceRange.endInclusive)
    fun factor(temperature: CalibrationTemperature?, coefficient: Double = CoefficientPerCelsius): Double? {
        if (temperature?.valid != true) return null
        require(coefficient.isFinite() && coefficient in 0.0..0.004)
        val denominator = 1.0 + coefficient * (temperature.celsius - ReferenceCelsius)
        return (1.0 / denominator).coerceIn(.85, 1.10)
    }
    fun usable(sample: CalibrationSample): Boolean = sample.temperature?.let {
        it.valid && sample.observation?.let { observation -> observation.nativeAt - it.estimatedAt in 0..1500 } == true
    } == true
    fun ratio(sample: CalibrationSample): Double = sample.measuredPedalWatts / sample.nativeWatts /
        (if (usable(sample)) factor(sample.temperature)!! else 1.0)
}
