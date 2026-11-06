package com.spop.poverlay.calibration

/** Rewrite calibration targets, retaining immutable pedal evidence for honest error diagnostics.
 * These edited records belong only to Grupetto profiles, never the sensor-board capture store.
 */
internal fun samplesForCalibrationTable(samples: List<CalibrationSample>, table: GrupettoCalibrationTable): List<CalibrationSample> =
    samples.map { sample ->
        val target = table.corrected(sample.nativeWatts, sample.ad, sample.rpm)
        if (target == sample.pedalWatts) sample else sample.copy(
            pedalWatts = target, recordedPedalWatts = sample.measuredPedalWatts)
    }

/** A persisted table is the common state for recordings and edits. Old deliveries cannot replay. */
internal fun updateUnifiedCalibration(capture: CalibrationCapture, previous: GrupettoCalibrationProfile?,
    active: GrupettoCalibrationTable? = null): GrupettoCalibrationProfile {
    val prior = previous?.takeIf { it.matches(capture) }?.table
    val table = if (prior != null && capture.samples.none { it.capturedAt > prior.recordedThrough && it.usableForCalibration(capture.baseline) }) {
        prior.copy(sampleCount = capture.samples.size, activeComparison = null).withCalibrationCoverage(capture.samples)
    } else GrupettoCalibrationFitter.fit(capture.baseline, capture.samples, active, seed = prior,
        cadenceReference = PelotonCadenceReference.forCapture(capture))
    return GrupettoCalibrationProfile(capture, table).withActivationReadiness()
}

/** Saving an edit changes the recordings and shared surface once. No offset survives to reapply. */
internal fun applyUnifiedManualEdit(profile: GrupettoCalibrationProfile, edited: GrupettoCalibrationTable,
    now: Long): GrupettoCalibrationProfile {
    require(profile.capture.baseline == edited.baseline && edited.gains.size == 496 &&
        edited.gains.all { it.isFinite() && it in CalibrationCorrectionLimits.logRange })
    val capture = profile.capture.copy(samples = samplesForCalibrationTable(profile.capture.samples, edited))
    val through = maxOf(now, edited.recordedThrough, profile.table?.recordedThrough ?: Long.MIN_VALUE,
        capture.samples.maxOfOrNull { it.capturedAt } ?: Long.MIN_VALUE)
    val errors = eligibleCalibrationErrorSamples(capture.baseline, capture.samples)
    val rawRmse = errors.takeIf { it.isNotEmpty() }?.let { values -> kotlin.math.sqrt(values.map {
        val error = it.nativeWatts - it.measuredPedalWatts
        error * error
    }.average()) } ?: edited.beforeRmse
    val correctedRmse = errors.takeIf { it.isNotEmpty() }?.let { values -> kotlin.math.sqrt(values.map {
        val error = edited.corrected(it.nativeWatts, it.ad, it.rpm) - it.measuredPedalWatts
        error * error
    }.average()) } ?: edited.afterRmse
    val table = edited.copy(ready = true, sampleCount = capture.samples.size, recordedThrough = through,
        beforeRmse = rawRmse, afterRmse = correctedRmse,
        activeComparison = null).withCalibrationCoverage(capture.samples)
    return GrupettoCalibrationProfile(capture, table).withActivationReadiness()
}
