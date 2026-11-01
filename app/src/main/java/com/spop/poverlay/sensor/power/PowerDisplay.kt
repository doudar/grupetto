package com.spop.poverlay.sensor.power

import kotlin.math.roundToInt

internal fun telemetryWatts(watts: Float): Int = if (watts.isFinite()) watts.coerceIn(0f, 32767f).roundToInt() else 0

internal fun calibrationSensorLabel(active: Boolean, recording: Boolean, meterName: String?): String =
    (if (active) "Using calibrated built-in power" else meterName ?: "Using built-in power") +
        if (meterName != null && recording) " and calibrating built-in power" else ""

enum class DisplayPowerSource(val label: String) { Bike("Power"), Pedals("Pedals"), Corrected("Corrected") }

/** The main overlay number and outgoing telemetry use this same selected value. */
data class PowerDisplay(val watts: Float, val source: DisplayPowerSource = DisplayPowerSource.Bike,
    val nativeWatts: Float = watts, val pedalWatts: Float? = null) {
    val comparison: String? get() = when {
        source == DisplayPowerSource.Corrected && pedalWatts != null -> "Pedals %.0f W".format(pedalWatts)
        source == DisplayPowerSource.Corrected || source == DisplayPowerSource.Pedals -> "Bike %.0f W".format(nativeWatts)
        else -> null
    }
}

internal fun selectPowerDisplay(bike: PowerDisplay, pedals: Float?): PowerDisplay = when {
    bike.source == DisplayPowerSource.Corrected -> bike.copy(pedalWatts = pedals)
    pedals != null -> PowerDisplay(pedals, DisplayPowerSource.Pedals, bike.nativeWatts, pedals)
    else -> bike.copy(pedalWatts = null)
}
