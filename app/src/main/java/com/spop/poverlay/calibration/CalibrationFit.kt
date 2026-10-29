package com.spop.poverlay.calibration

import kotlin.math.*

/** Raw AD is essential: displayed resistance is quantized and can be recalibrated. */
data class CalibrationFrame(val timestamp: Long, val ad: Int, val rpm: Double,
    val nativeWatts: Double, val frameSerial: String, val powerSource: Int,
    val resistance: Int? = null)

data class CalibrationSample(val ad: Double, val rpm: Double, val nativeWatts: Double,
    val pedalWatts: Double, val capturedAt: Long)

internal const val MinimumCalibrationWatts = 5
internal val CalibrationPowerRange = MinimumCalibrationWatts.toDouble()..1500.0

data class CalibrationFit(val points: List<Int>, val supportedPoints: List<Int>,
    val conflicts: Int = 0, val validationCount: Int = 0,
    val beforeRmse: Double? = null, val afterRmse: Double? = null,
    val ready: Boolean = false, val message: String = "Ride steady at several resistances and cadences.")

internal fun validCalibrationTable(table: List<Int>) = table.size == 31 && table.all { it in 1..1_000_000 } &&
    table.zipWithNext().all { (a, b) -> b > a } && table.zipWithNext().any { (a, b) -> b - a >= 20 }

internal fun median(values: List<Double>): Double = values.sorted().let {
    if (it.size % 2 == 0) (it[it.size / 2 - 1] + it[it.size / 2]) / 2 else it[it.size / 2]
}

enum class CapturePhase(val cue: String) {
    Waiting("Connect pedals"), Pedal("Pedal comfortably"), Settling("Keep the knob still"),
    LowPower("Keep both meters at ${MinimumCalibrationWatts} W+"),
    Holding("Hold this pace"), CadenceChanged("Smooth your cadence"),
    ResistanceChanged("Keep the knob still"), PowerChanged("Let power settle"), Saved("Effort saved"),
}

data class CaptureProgress(val phase: CapturePhase = CapturePhase.Waiting, val seconds: Int = 0) {
    val fraction get() = (seconds / 8f).coerceIn(0f, 1f)
    val remaining get() = (8 - seconds).coerceAtLeast(0)
}

/** One independent 8-second effort; discard its first three seconds for meter lag.
 * Allow human cadence variation and one isolated power outlier, but reject ramps.
 * Never count a cached pedal packet twice or learn through a connection/clock discontinuity.
 */
class CalibrationWindow(private val table: List<Int>) {
    private data class Pairing(val frame: CalibrationFrame, val pedal: Double)
    private val window = mutableListOf<Pairing>()
    private var lastPedalAt = Long.MIN_VALUE
    private var address: String? = null
    var progress = CaptureProgress()
        private set
    fun reset(phase: CapturePhase = CapturePhase.Waiting) {
        window.clear(); lastPedalAt = Long.MIN_VALUE; address = null
        progress = CaptureProgress(phase)
    }
    fun add(frame: CalibrationFrame, pedalWatts: Int, pedalAt: Long, meter: String,
            now: Long, capturedAt: Long): CalibrationSample? {
        if (frame.powerSource != 1 || frame.rpm !in 45.0..120.0 ||
            frame.nativeWatts !in CalibrationPowerRange || pedalWatts.toDouble() !in CalibrationPowerRange ||
            frame.ad !in table.first()..table.last() ||
            now - frame.timestamp !in 0..1000 || now - pedalAt !in 0..1500) {
            reset(when {
                now - frame.timestamp !in 0..1000 || now - pedalAt !in 0..1500 -> CapturePhase.Waiting
                frame.nativeWatts < MinimumCalibrationWatts || pedalWatts < MinimumCalibrationWatts -> CapturePhase.LowPower
                else -> CapturePhase.Pedal
            }); return null
        }
        if (address != null && address != meter) reset()
        address = meter
        if (pedalAt == lastPedalAt) { progress = CaptureProgress(CapturePhase.Waiting); return null }
        if (lastPedalAt != Long.MIN_VALUE && pedalAt - lastPedalAt !in 500..2000) window.clear()
        lastPedalAt = pedalAt
        if (window.lastOrNull()?.let { frame.timestamp - it.frame.timestamp !in 500..2000 } == true) window.clear()
        window += Pairing(frame, pedalWatts.toDouble())
        val i = table.indexOfLast { it <= frame.ad }.coerceIn(0, 29)
        val adTolerance = max(3.0, (table[i + 1] - table[i]) * .10)
        fun spread(v: List<Double>) = v.maxOrNull()!! - v.minOrNull()!!
        val cadenceChanged = spread(window.map { it.frame.rpm }) > 8
        val resistanceChanged = spread(window.map { it.frame.ad.toDouble() }) > adTolerance
        if (cadenceChanged || resistanceChanged) {
            progress = CaptureProgress(if (resistanceChanged) CapturePhase.ResistanceChanged else CapturePhase.CadenceChanged)
            window.clear(); window += Pairing(frame, pedalWatts.toDouble()); return null
        }
        val elapsed = frame.timestamp - window.first().frame.timestamp
        progress = CaptureProgress(if (elapsed < 3000) CapturePhase.Settling else CapturePhase.Holding,
            (elapsed / 1000).toInt().coerceAtMost(7))
        if (elapsed < 7000 || window.size < 8) return null
        val settled = window.filter { it.frame.timestamp - window.first().frame.timestamp >= 3000 }
        window.clear()
        if (settled.size < 5) return null
        val native = settled.map { it.frame.nativeWatts }
        val pedals = settled.map { it.pedal }
        fun steady(values: List<Double>): Boolean {
            val centre = median(values)
            // Preserve the usual 20 W allowance, but do not treat a large low-power ramp as steady.
            val tolerance = max(max(5.0, min(20.0, centre * .5)), centre * .12)
            // One isolated pedal spike is okay; sustained changes and two outliers are not.
            val inliers = values.filter { abs(it - centre) <= tolerance }
            return inliers.size >= values.size - 1 && spread(inliers) <= tolerance &&
                abs(median(values.take(2)) - median(values.takeLast(2))) <= tolerance
        }
        if (!steady(native) || !steady(pedals)) {
            progress = CaptureProgress(CapturePhase.PowerChanged); return null
        }
        progress = CaptureProgress(CapturePhase.Saved, 8)
        return CalibrationSample(median(settled.map { it.frame.ad.toDouble() }),
            median(settled.map { it.frame.rpm }), median(native), median(pedals), capturedAt)
    }
}

/** Empirical inverse fit: at each Lxx point, find the AD where pedal watts equal the
 * original point's native watts, separately by cadence. No guessed cadence exponent,
 * extrapolation, or assumption that the tablet's Rexon/Tonic table matches board firmware.
 * A candidate remains a hypothesis about the board's Lxx mapping, never a write command.
 */
object CalibrationFitter {
    private data class Node(val ad: Double, val native: Double, val pedals: Double)
    private data class Curve(val cadence: Int, val nodes: List<Node>)
    private fun interpolate(points: List<Pair<Double, Double>>, x: Double,
                            maxSegmentSpan: Double = Double.POSITIVE_INFINITY): Double? {
        if (points.size < 2 || x < points.first().first || x > points.last().first) return null
        val pair = points.zipWithNext().firstOrNull { (a, b) -> x >= a.first && x <= b.first } ?: return null
        val (a, b) = pair
        if (b.first <= a.first || b.first - a.first > maxSegmentSpan) return null
        return a.second + (x - a.first) / (b.first - a.first) * (b.second - a.second)
    }
    private fun coordinate(table: List<Int>, ad: Double): Double? =
        interpolate(table.mapIndexed { i, v -> v.toDouble() to i.toDouble() }, ad)
    private fun adAt(table: List<Int>, coordinate: Double): Double? =
        interpolate(table.mapIndexed { i, v -> i.toDouble() to v.toDouble() }, coordinate)

    fun fit(baseline: List<Int>, samples: List<CalibrationSample>): CalibrationFit {
        require(validCalibrationTable(baseline))
        val usable = samples.filter { it.ad.isFinite() && it.rpm in 45.0..120.0 &&
            it.nativeWatts in 30.0..1500.0 && it.pedalWatts in 30.0..1500.0 &&
            it.ad >= baseline.first() && it.ad <= baseline.last() }
        // Split within each local cell, so validation repeats are never used to fit that cell.
        val grouped = usable.groupBy { (it.rpm / 5).roundToInt() * 5 to
            ((coordinate(baseline, it.ad) ?: -100.0) * 10).roundToInt() }
        val training = mutableMapOf<Int, MutableList<Node>>()
        val validation = mutableListOf<Pair<Int, CalibrationSample>>()
        grouped.forEach { (cell, entries) ->
            if (entries.size < 3) return@forEach
            val ordered = entries.sortedBy { it.capturedAt }
            val train = ordered.filterIndexed { i, _ -> i % 3 != 2 }
            validation += ordered.filterIndexed { i, _ -> i % 3 == 2 }.map { cell.first to it }
            training.getOrPut(cell.first) { mutableListOf() } += Node(
                median(train.map { it.ad }), median(train.map { it.nativeWatts }), median(train.map { it.pedalWatts }))
        }
        val curves = training.mapNotNull { (rpm, nodes) ->
            val sorted = nodes.sortedBy { it.ad }
            // Reject noisy/non-invertible curves instead of forcing them into a usable fit.
            if (sorted.size < 3 || sorted.zipWithNext().any { (a, b) ->
                    b.ad <= a.ad || b.native <= a.native || b.pedals <= a.pedals }) null
            else Curve(rpm, sorted)
        }
        val points = baseline.toMutableList()
        val supported = mutableListOf<Int>()
        var conflicts = 0
        for (i in 1..29) {
            val gap = min(baseline[i] - baseline[i - 1], baseline[i + 1] - baseline[i])
            val candidates = curves.mapNotNull { curve ->
                val native = interpolate(curve.nodes.map { it.ad to it.native }, baseline[i].toDouble(), gap * 2.0)
                    ?: return@mapNotNull null
                val bracket = curve.nodes.zipWithNext().firstOrNull { (a, b) ->
                    native >= a.pedals && native <= b.pedals && b.ad - a.ad <= gap * 2.0
                } ?: return@mapNotNull null
                val corrected = interpolate(listOf(bracket.first.pedals to bracket.first.ad,
                    bracket.second.pedals to bracket.second.ad), native)
                    ?: return@mapNotNull null
                curve.cadence to corrected
            }
            if (candidates.size < 2 || candidates.maxOf { it.first } - candidates.minOf { it.first } < 10) continue
            val values = candidates.map { it.second }
            if (values.maxOrNull()!! - values.minOrNull()!! > gap * .25) { conflicts++; continue }
            val proposed = median(values).roundToInt()
            // Do not silently clip a large correction and pretend it was learned.
            if (abs(proposed - baseline[i]) > gap * .75) continue
            points[i] = proposed
            supported += i
        }
        if (!validCalibrationTable(points)) return CalibrationFit(baseline, emptyList(), conflicts,
            message = "Candidate points cross. Collect more steady data; nothing will be applied.")
        var before = 0.0
        var after = 0.0
        val errorsByCadence = mutableMapOf<Int, Pair<Double, Double>>()
        var count = 0
        validation.forEach { (rpm, sample) ->
            val x = coordinate(points, sample.ad) ?: return@forEach
            // Only evaluate intervals actually changed; unchanged regions must not dilute error.
            val lo = floor(x).toInt().coerceIn(0, 29)
            if (points[lo] == baseline[lo] && points[lo + 1] == baseline[lo + 1]) return@forEach
            val virtualAd = adAt(baseline, x) ?: return@forEach
            val curve = curves.firstOrNull { it.cadence == rpm } ?: return@forEach
            val predicted = interpolate(curve.nodes.map { it.ad to it.native }, virtualAd,
                (baseline[lo + 1] - baseline[lo]) * 2.0) ?: return@forEach
            val oldError = (sample.nativeWatts - sample.pedalWatts).pow(2)
            val newError = (predicted - sample.pedalWatts).pow(2)
            before += oldError; after += newError; count++
            val previous = errorsByCadence[rpm] ?: (0.0 to 0.0)
            errorsByCadence[rpm] = previous.first + oldError to previous.second + newError
        }
        val oldRmse = if (count > 0) sqrt(before / count) else null
        val newRmse = if (count > 0) sqrt(after / count) else null
        val ready = supported.size >= 4 && count >= 12 && errorsByCadence.size >= 2 &&
            oldRmse != null && newRmse != null && newRmse <= oldRmse * .9 && oldRmse - newRmse >= 1 &&
            errorsByCadence.values.all { it.second <= it.first }
        return CalibrationFit(points, supported, conflicts, count, oldRmse, newRmse, ready,
            when {
                ready -> "Candidate ready for review. Sensor-board writing is not available."
                conflicts > 0 -> "Cadence-dependent disagreement: $conflicts points kept unchanged."
                supported.isEmpty() -> "Repeat steady efforts at nearby resistances and cadences at least 10 rpm apart."
                else -> "Collect more repeat efforts to validate the candidate on separate samples."
            })
    }
}
