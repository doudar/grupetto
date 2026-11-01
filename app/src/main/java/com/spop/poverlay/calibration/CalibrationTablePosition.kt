package com.spop.poverlay.calibration

import kotlin.math.roundToInt

data class CalibrationTablePosition(val frame: CalibrationFrame, val baseline: List<Int>)

internal fun liveTableCell(position: CalibrationTablePosition?, baseline: List<Int>?, now: Long): Pair<Int, Int>? {
    val current = position ?: return null
    val frame = current.frame
    if (now - frame.timestamp !in 0..2000 || frame.rpm !in 45.0..120.0 || frame.powerSource != 1) return null
    if (baseline != null && baseline != current.baseline) return null
    val x = calibrationCoordinate(current.baseline, frame.ad.toDouble()) ?: return null
    return x.roundToInt().coerceIn(0, 30) to ((frame.rpm - 45) / 5).roundToInt().coerceIn(0, 15)
}
