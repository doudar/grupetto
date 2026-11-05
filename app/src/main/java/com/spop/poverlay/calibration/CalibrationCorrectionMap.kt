package com.spop.poverlay.calibration

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.exp

internal val CorrectionOverColor = Color(0xFFFF2B2B)
internal val CorrectionZeroColor = Color(0xFFE2E8F0)
internal val CorrectionUnderColor = Color(0xFF1239B5)
internal val CorrectionUnknownColor = Color(0xFF273443)

/** Percentage change relative to raw bike watts, on a fixed -10%..+10% color scale. */
internal fun correctionColor(percent: Double?): Color {
    if (percent == null || !percent.isFinite()) return CorrectionUnknownColor
    val fraction = (percent / 10).coerceIn(-1.0, 1.0).toFloat()
    return if (fraction < 0) lerp(CorrectionZeroColor, CorrectionOverColor, -fraction)
        else lerp(CorrectionZeroColor, CorrectionUnderColor, fraction)
}

/** Only populated cells are colored. Lookup fallback does not invent observed coverage. */
internal fun calibrationCorrectionPercent(table: GrupettoCalibrationTable, samples: List<CalibrationSample>,
    includeManual: Boolean = true): List<Double?> {
    val counts = table.effectiveCellSampleCounts()
    val recorded = samples.filter { it.usableForCalibration(table.baseline) }
        .mapNotNull { calibrationCellIndex(table.baseline, it.ad, it.rpm) }.toSet()
    return List(496) { index ->
        if (counts[index] > 0 && (includeManual || index in recorded))
            100 * (exp(table.gains[index]) - 1) else null
    }
}

internal fun GrupettoCalibrationTable.withCalibrationCoverage(samples: List<CalibrationSample>): GrupettoCalibrationTable {
    val measured = calibrationCorrectionPercent(this, samples, includeManual = false)
    return copy(streamSeconds = samples.count { it.observation != null },
        legacyEffortCount = samples.count { it.observation == null },
        correctionPercent = calibrationCorrectionPercent(this, samples),
        recordedCoverage = measured.indices.filterTo(mutableSetOf()) { measured[it] != null })
}
