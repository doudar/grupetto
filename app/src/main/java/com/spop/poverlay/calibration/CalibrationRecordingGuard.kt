package com.spop.poverlay.calibration

/** Reject a recording request without silently ignoring the user's tap. */
internal fun requireCalibrationMeter(meterReady: Boolean, showMessage: (String) -> Unit): Boolean {
    if (!meterReady) showMessage("Connect a power meter first")
    return meterReady
}
