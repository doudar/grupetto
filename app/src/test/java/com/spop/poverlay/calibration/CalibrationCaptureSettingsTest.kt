package com.spop.poverlay.calibration

import org.junit.Assert.*
import org.junit.Test

class CalibrationCaptureSettingsTest {
    private val baseline = List(31) { 1000 + it * 100 }
    private fun add(window: CalibrationWindow, second: Int, ad: Int = 2000, watts: Int = 220): CalibrationSample? {
        val now = second * 1000L
        return window.add(CalibrationFrame(now, ad, 80.0, 200.0, "bike", 1), watts, now, "meter", now, now)
    }
    @Test fun configurableSettleTimeDiscardsLagAndStillRequiresFiveStableReadings() {
        listOf(0, 3, 5, 15).forEach { settle ->
            val settings = CalibrationCaptureSettings(settleSeconds = settle)
            val window = CalibrationWindow(baseline, settings)
            repeat(settings.totalSeconds - 1) { second ->
                assertNull(add(window, second, watts = if (second < settle) 100 else 220))
                assertEquals(settings.totalSeconds, window.progress.totalSeconds)
            }
            assertEquals(220.0, add(window, settings.totalSeconds - 1)!!.pedalWatts, 0.0)
            assertEquals(CapturePhase.Saved, window.progress.phase)
            assertEquals(1f, window.progress.fraction)
        }
    }
    @Test fun resistanceMovementSettingChangesTheAllowedSpread() {
        val strict = CalibrationWindow(baseline)
        val relaxed = CalibrationWindow(baseline, CalibrationCaptureSettings(resistanceMovementPercent = 25))
        repeat(7) { second ->
            assertNull(add(strict, second, ad = 2000 + second % 2 * 20))
            assertNull(add(relaxed, second, ad = 2000 + second % 2 * 20))
        }
        assertNull(add(strict, 7, ad = 2020))
        assertEquals(CapturePhase.ResistanceChanged, strict.progress.phase)
        assertNotNull(add(relaxed, 7, ad = 2020))
        assertEquals(3.0, CalibrationCaptureSettings().resistanceTolerance(10), 0.0)
    }
    @Test fun invalidSettingsAreRejected() {
        assertTrue(runCatching { CalibrationCaptureSettings(-1) }.isFailure)
        assertTrue(runCatching { CalibrationCaptureSettings(16) }.isFailure)
        assertTrue(runCatching { CalibrationCaptureSettings(resistanceMovementPercent = 0) }.isFailure)
        assertTrue(runCatching { CalibrationCaptureSettings(resistanceMovementPercent = 101) }.isFailure)
    }
}
