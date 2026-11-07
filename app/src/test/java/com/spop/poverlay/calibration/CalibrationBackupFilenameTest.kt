package com.spop.poverlay.calibration

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class CalibrationBackupFilenameTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun instant(value: String): Date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        .apply { timeZone = utc }.parse(value)!!

    @Test fun padsMonthAndDayAndUsesTwoDigitYear() {
        assertEquals("grupettoTable010926.json", calibrationBackupFilename(instant("2026-01-09 12:00:00"), utc))
        assertEquals("grupettoTable110726.json", calibrationBackupFilename(instant("2026-11-07 12:00:00"), utc))
    }

    @Test fun usesCalendarYearAcrossNewYear() {
        assertEquals("grupettoTable123125.json", calibrationBackupFilename(instant("2025-12-31 23:59:59"), utc))
        assertEquals("grupettoTable010126.json", calibrationBackupFilename(instant("2026-01-01 00:00:00"), utc))
        assertEquals("grupettoTable010100.json", calibrationBackupFilename(instant("2000-01-01 00:00:00"), utc))
    }

    @Test fun preservesLeapDay() {
        assertEquals("grupettoTable022928.json", calibrationBackupFilename(instant("2028-02-29 12:00:00"), utc))
    }

    @Test fun usesLocalDateRatherThanUtcNearMidnight() {
        assertEquals("grupettoTable123125.json", calibrationBackupFilename(instant("2026-01-01 01:00:00"),
            TimeZone.getTimeZone("America/Los_Angeles")))
        assertEquals("grupettoTable010126.json", calibrationBackupFilename(instant("2025-12-31 12:00:00"),
            TimeZone.getTimeZone("Pacific/Kiritimati")))
    }

    @Test fun readsTheCurrentDeviceTimeZoneOnEveryCall() {
        val previous = TimeZone.getDefault()
        try {
            val date = instant("2026-01-01 01:00:00")
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("grupettoTable123125.json", calibrationBackupFilename(date))
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("grupettoTable010126.json", calibrationBackupFilename(date))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun keepsAsciiDigitsAndGregorianYearInOtherDeviceLocales() {
        val previous = Locale.getDefault()
        try {
            listOf(Locale("ar", "EG"), Locale("th", "TH")).forEach { locale ->
                Locale.setDefault(locale)
                assertEquals("grupettoTable010926.json", calibrationBackupFilename(instant("2026-01-09 12:00:00"), utc))
            }
        } finally { Locale.setDefault(previous) }
    }
}
