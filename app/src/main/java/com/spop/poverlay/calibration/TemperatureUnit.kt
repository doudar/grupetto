package com.spop.poverlay.calibration

import java.util.Locale

/** Display preference only; estimator and calibration storage keep their existing units. */
enum class TemperatureUnit(val symbol: String, val nextLabel: String) {
    Fahrenheit("°F", "Switch to Celsius"), Celsius("°C", "Switch to Fahrenheit");

    fun fromFahrenheit(fahrenheit: Double): Double =
        if (this == Fahrenheit) fahrenheit else (fahrenheit - 32.0) / 1.8

    fun toFahrenheit(value: Double): Double =
        if (this == Fahrenheit) value else value * 1.8 + 32.0

    fun other(): TemperatureUnit = if (this == Fahrenheit) Celsius else Fahrenheit
}

/** Keep the unrounded Fahrenheit draft separately so repeated toggles never drift. */
internal fun roomTemperatureInput(fahrenheit: Double, unit: TemperatureUnit): String =
    String.format(Locale.US, "%.2f", unit.fromFahrenheit(fahrenheit)).trimEnd('0').trimEnd('.')
