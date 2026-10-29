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

/** The fitted gain supplies the exact correction percentage; nearby efforts mark observed coverage. */
internal fun calibrationCorrectionPercent(table: GrupettoCalibrationTable, samples: List<CalibrationSample>): List<Double?> {
    data class Reference(val x: Double, val rpm: Double)
    val references = samples.filter { it.nativeWatts in CalibrationPowerRange && it.rpm in 45.0..120.0 }
        .mapNotNull { sample -> calibrationCoordinate(table.baseline, sample.ad)?.let { Reference(it, sample.rpm) } }
        .distinct()
    return List(496) { index ->
        val x = (index / 16).toDouble()
        val rpm = 45.0 + index % 16 * 5
        if (references.any { abs(it.x - x) <= 2 && abs(it.rpm - rpm) <= 10 })
            100 * (exp(table.gains[index]) - 1) else null
    }
}
