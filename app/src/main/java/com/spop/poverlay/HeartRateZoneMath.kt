package com.spop.poverlay

import kotlin.math.abs
import kotlin.math.roundToInt

/** The top of zones 1 to 5, as a percentage of maximum heart rate (zone 5 ends at the maximum). */
private val ZoneBoundaryPercents = listOf(60, 70, 80, 90, 100)

private fun boundariesFrom(anchor: Int, anchorIndex: Int): List<Int> {
    val maxHeartRate = anchor * 100.0 / ZoneBoundaryPercents[anchorIndex]
    return ZoneBoundaryPercents.map { (maxHeartRate * it / 100.0).roundToInt() }
}

/**
 * Called after the boundary at [anchorIndex] was edited, with all five boundaries [before] and
 * [after] the edit. If that boundary did not change, nothing happens. Otherwise the others are
 * calculated from it at 60/70/80/90/100% of max heart rate:
 *  - blank boundaries are always filled in;
 *  - boundaries that were still the automatic values for the old number (give or take 1 bpm)
 *    follow the new number;
 *  - boundaries someone typed in by hand are left alone.
 */
internal fun fillZoneBoundaries(before: List<Int?>, after: List<Int?>, anchorIndex: Int): List<Int?> {
    if (before.size != ZoneBoundaryPercents.size || after.size != before.size) return after
    // Merely tapping into a box must not recalculate anything.
    if (before.getOrNull(anchorIndex) == after.getOrNull(anchorIndex)) return after
    val anchor = after.getOrNull(anchorIndex)?.takeIf { it > 0 } ?: return after
    val oldAnchor = before[anchorIndex]?.takeIf { it > 0 }
    val oldAuto = oldAnchor?.let { boundariesFrom(it, anchorIndex) }
    val newAuto = boundariesFrom(anchor, anchorIndex)
    return after.mapIndexed { index, value ->
        when {
            index == anchorIndex -> value
            value == null -> newAuto[index]
            oldAuto != null && abs(value - oldAuto[index]) <= 1 -> newAuto[index]
            else -> value
        }
    }
}
