package com.spop.poverlay.calibration

/** Normal recording publishes automatically. Retain a recovery action only for a distinct ready draft. */
internal fun hasPendingCalibrationFit(active: Boolean, recording: Boolean,
    draft: GrupettoCalibrationTable?, displayed: GrupettoCalibrationTable?,
    draftCount: Int, activeCount: Int): Boolean =
    active && !recording && draft?.ready == true && displayed != null &&
        (draftCount != activeCount || draft.baseline != displayed.baseline ||
            draft.gains != displayed.gains || draft.manualOffsets != displayed.manualOffsets ||
            draft.manualCoverage != displayed.manualCoverage)
