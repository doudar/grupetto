package com.spop.poverlay.calibration

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Suggested save-dialog name, using the device's local calendar date at each save. */
internal fun calibrationBackupFilename(date: Date = Date(), timeZone: TimeZone = TimeZone.getDefault()): String {
    val stamp = SimpleDateFormat("MMddyy", Locale.US).apply { this.timeZone = timeZone }.format(date)
    return "grupettoTable$stamp.json"
}
