package com.spop.poverlay.overlay

import kotlin.math.roundToInt

internal const val ShifterWidthDp = 64
internal const val ShifterHeightDp = 112
internal const val ShifterBottomMarginDp = 12

internal fun normalizedShifterInset(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

internal fun showRideShifters(connected: Boolean, enabled: Boolean, configurationVisible: Boolean): Boolean =
    connected && enabled && !configurationVisible

/** Both windows use this same distance, measured from their respective screen edge. */
internal fun shifterEdgeInset(screenWidth: Int, density: Float, position: Float): Int =
    (8f * density + screenWidth * normalizedShifterInset(position) / 8f).roundToInt()
