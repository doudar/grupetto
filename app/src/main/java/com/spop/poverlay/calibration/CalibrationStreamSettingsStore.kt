package com.spop.poverlay.calibration

import android.content.SharedPreferences

internal class CalibrationStreamSettingsStore(private val preferences: SharedPreferences) {
    fun load() = CalibrationStreamSettings.fromStored(preferences.all)
    fun save(settings: CalibrationStreamSettings) {
        preferences.edit().putInt("settleSeconds", settings.settleSeconds)
            .putFloat("cadenceWindowRpm", settings.cadenceWindowRpm.toFloat())
            .putInt("resistanceWindowPercent", settings.resistanceWindowPercent)
            .putBoolean("outlierRejection", settings.outlierRejection)
            .putFloat("outlierTolerancePercent", settings.outlierTolerancePercent.toFloat())
            .putFloat("outlierNoiseMultiplier", settings.outlierNoiseMultiplier.toFloat())
            .putInt("newLevelReadings", settings.newLevelReadings).apply()
    }
}
