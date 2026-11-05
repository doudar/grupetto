package com.spop.poverlay.calibration

/** Collection tolerances are independent of the 5-rpm storage grid. Each accepted pair keeps
 * its exact cadence/AD and updates only its actual cell, even when the hold spans a boundary. */
data class CalibrationStreamSettings(val settleSeconds: Int = 2,
    val cadenceWindowRpm: Double = 5.0, val resistanceWindowPercent: Int = 2,
    val outlierRejection: Boolean = true, val outlierTolerancePercent: Double = 3.0,
    val outlierNoiseMultiplier: Double = 4.0, val newLevelReadings: Int = 3) {
    init {
        require(settleSeconds in 0..15)
        require(cadenceWindowRpm.isFinite() && cadenceWindowRpm in .5..15.0)
        require(resistanceWindowPercent in 1..100)
        require(outlierTolerancePercent.isFinite() && outlierTolerancePercent in 1.0..30.0)
        require(outlierNoiseMultiplier.isFinite() && outlierNoiseMultiplier in 1.0..10.0)
        require(newLevelReadings in 2..10)
    }
    companion object {
        val MoreForgiving get() = CalibrationStreamSettings(cadenceWindowRpm = 5.0,
            resistanceWindowPercent = 10, outlierTolerancePercent = 6.0)

        /** Malformed preferences fall back field-by-field instead of crashing or opening gates. */
        fun fromStored(values: Map<String, *>): CalibrationStreamSettings {
            val defaults = CalibrationStreamSettings()
            fun number(key: String, range: ClosedFloatingPointRange<Double>, fallback: Double) =
                (values[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it in range } ?: fallback
            fun integer(key: String, range: IntRange, fallback: Int): Int =
                (values[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it == it.toInt().toDouble() && it.toInt() in range }?.toInt() ?: fallback
            return CalibrationStreamSettings(integer("settleSeconds", 0..15, defaults.settleSeconds),
                number("cadenceWindowRpm", .5..15.0, defaults.cadenceWindowRpm),
                integer("resistanceWindowPercent", 1..100, defaults.resistanceWindowPercent),
                values["outlierRejection"] as? Boolean ?: defaults.outlierRejection,
                number("outlierTolerancePercent", 1.0..30.0, defaults.outlierTolerancePercent),
                number("outlierNoiseMultiplier", 1.0..10.0, defaults.outlierNoiseMultiplier),
                integer("newLevelReadings", 2..10, defaults.newLevelReadings))
        }
    }
}
