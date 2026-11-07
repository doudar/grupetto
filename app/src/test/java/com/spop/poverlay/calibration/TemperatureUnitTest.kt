package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class TemperatureUnitTest {
    @Test fun convertsCommonTemperaturesAndValidationBounds() {
        listOf(32.0 to 0.0, 68.0 to 20.0, 86.0 to 30.0, 110.0 to (78.0 / 1.8)).forEach { (f, c) ->
            assertEquals(c, TemperatureUnit.Celsius.fromFahrenheit(f), 1e-9)
            assertEquals(f, TemperatureUnit.Celsius.toFahrenheit(c), 1e-9)
            assertEquals(f, TemperatureUnit.Fahrenheit.fromFahrenheit(f), 0.0)
            assertEquals(f, TemperatureUnit.Fahrenheit.toFahrenheit(f), 0.0)
        }
        assertTrue(validRoomTemperature(TemperatureUnit.Celsius.toFahrenheit(0.0)))
        assertFalse(validRoomTemperature(TemperatureUnit.Celsius.toFahrenheit(-1.0)))
        assertFalse(validRoomTemperature(TemperatureUnit.Celsius.toFahrenheit(44.0)))
    }

    @Test fun inputFormattingIsCompactAndLocaleIndependent() {
        assertEquals("72", roomTemperatureInput(72.0, TemperatureUnit.Fahrenheit))
        assertEquals("22.22", roomTemperatureInput(72.0, TemperatureUnit.Celsius))
        assertEquals("30", roomTemperatureInput(86.0, TemperatureUnit.Celsius))
        assertEquals(TemperatureUnit.Fahrenheit, TemperatureUnit.Fahrenheit.other().other())
    }

    @Test fun changingDisplayUnitDoesNotAdvanceOrAlterTheThermalModel() {
        val model = FlywheelTemperatureModel(0, 1000)
        model.observe(0, 200.0)
        model.advance(1000)
        val before = model.state
        val checkpoint = model.checkpoint(2000)
        model.setDisplayUnit(TemperatureUnit.Celsius)
        assertEquals(before.copy(displayUnit = TemperatureUnit.Celsius), model.state)
        assertEquals(checkpoint, model.checkpoint(2000))
        model.setDisplayUnit(TemperatureUnit.Fahrenheit)
        assertEquals(before, model.state)
        assertEquals(thermalStep(before.celsius, (72.0 - 32) / 1.8, 200.0, 1.0),
            model.advance(2000).celsius, 1e-9)
    }
}
