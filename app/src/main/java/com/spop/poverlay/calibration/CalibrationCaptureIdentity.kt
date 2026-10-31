package com.spop.poverlay.calibration

/** Empty capture files contain identity metadata, but no observations to invalidate. */
internal fun calibrationCaptureMismatches(saved: CalibrationCapture, current: CalibrationCapture): List<String> = buildList {
    if (saved.frameSerial != current.frameSerial) add("bike identity")
    if (saved.serviceVersion != current.serviceVersion) add("service version")
    if (saved.baseline != current.baseline) add("resistance baseline")
}

internal fun resolveCalibrationCapture(saved: CalibrationCapture?, current: CalibrationCapture): CalibrationCapture {
    require(current.samples.isEmpty())
    if (saved == null) return current
    val mismatches = calibrationCaptureMismatches(saved, current)
    if (mismatches.isEmpty()) return if (saved.meterAddress == current.meterAddress) saved
        else saved.copy(meterAddress = current.meterAddress)
    if (saved.samples.isEmpty()) return current
    error("Saved capture differs in ${mismatches.joinToString()}. " +
        "Its ${saved.samples.size} efforts were kept. Restore the original setup or clear the saved capture to start a new one.")
}

/** Meter identity is descriptive; bike/firmware/baseline compatibility still protects the fit. */
internal fun mergeCalibrationCaptures(saved: CalibrationCapture, incoming: CalibrationCapture?, clearedAt: Long): CalibrationCapture {
    if (incoming == null || calibrationCaptureMismatches(saved, incoming).isNotEmpty()) return saved
    val fresh = incoming.samples.filter { it.capturedAt >= clearedAt }
    if (fresh.isEmpty()) return saved
    return saved.copy(meterAddress = incoming.meterAddress,
        samples = (saved.samples + fresh).distinctBy { it.capturedAt }.sortedBy { it.capturedAt }.takeLast(3600))
}
