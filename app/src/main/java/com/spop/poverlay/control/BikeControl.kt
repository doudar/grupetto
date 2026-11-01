package com.spop.poverlay.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** Only the mainboard platform identifies a Bike+; Topaz tablets are shared with Row/Tread. */
fun supportsBikeControl(isPeloton: Boolean, platform: String?, model: String = ""): Boolean {
    if (!isPeloton) return false
    val machine = platform?.trim()?.lowercase()
    if (machine == "caesar" || machine == "aurora" || machine == "v1" ||
        com.spop.poverlay.util.isTreadPlatform(machine)) return false
    return machine == "titan" || com.spop.poverlay.util.isG700CrossTrainerModel(model)
}

data class BikeSample(val power: Float, val cadence: Float, val resistance: Int, val timestamp: Long)
enum class ControlMode { Manual, Erg, Simulation, Resistance }
data class ControlState(
    val connected: Boolean = false,
    val mode: ControlMode = ControlMode.Manual,
    val targetWatts: Int = 150,
    val targetResistance: Int = 0,
    val targetIncline: Float = 0f,
    val wattsPerShift: Int = 10,
    val inclineSensitivity: Float = 2f,
    val externalControl: String? = null,
    val shiftOffset: Int = 0,
    val shiftSize: Int = 2,
    val gain: Float = .007f,
    /** Modeled-jump ramp speed in resistance levels per second (rider preference). */
    val rampRate: Int = BikeControl.RAMP_RATE_DEFAULT,
    val permissionLostCount: Int = 0,
    val message: String = "Waiting for Bike+"
)
data class Simulation(val wind: Float, val grade: Float, val rolling: Float, val drag: Float) {
    // A repeatable resistance model: 2 Peloton points per percent grade, with wind and
    // rolling resistance expressed as equivalent grade at 30 km/h and 85 kg total mass.
    // This is a trainer feel model, not a claim of calibrated road power or rider mass.
    fun resistanceOffset(sensitivity: Float = 2f): Float {
        val airSpeed = 30f / 3.6f + wind
        val extraForce = drag * airSpeed * abs(airSpeed) - .51f * (30f / 3.6f) * (30f / 3.6f)
        return sensitivity * (grade + (rolling - .004f) * 100f + extraForce / (85f * 9.80665f) * 100f)
    }
}

/** Serialized, clock-injected control core. No Android stubs or sleeping threads in tests.
 * Motor transaction and proportional ERG approach inspired by dwj300's PR #50.
 */
class BikeControl(
    val supported: Boolean,
    private val clock: () -> Long,
    private val writeResistance: (Int) -> Boolean
) {
    private val mutableState = MutableStateFlow(ControlState())
    val state = mutableState.asStateFlow()
    private var sample: BikeSample? = null
    private var owner: String? = null
    private var filteredPower: Float? = null
    private var requestedResistance = 0f
    private var expectedResistance: Int? = null
    private var commandTime = 0L
    private var ackTimeoutMs = ACK_TIMEOUT_MS
    private var previousTick = 0L
    private var simBase = 0f
    private var simulation = Simulation(0f, 0f, .004f, .51f)
    private var pausedMode: ControlMode? = null
    private var pausedShift = 0
    private var pausedResistance = 0
    private var externalPower: com.spop.poverlay.sensor.power.ExternalPowerReading? = null
    // Feed-forward: jump toward the level the power model predicts for a new ERG target,
    // then hold off integral feedback until the motor and the reported power settle.
    val powerModel = PowerModel()
    private var feedForwardTarget: Int? = null
    private var feedForwardUntil = 0L
    private var feedForwardLevel = 0f
    // The settle window starts when the bike reports the modeled level, not when it was
    // commanded: on long jumps the motor runs slower than the command rate.
    private var feedForwardArrivalPending = false
    private var feedForwardDeadline = 0L
    // Smooth ramp toward the modeled level: one level per 100 ms tick, so the motor turns
    // continuously instead of moving in bursts between write/echo round trips.
    private var rampLevel: Float? = null
    private var rampUp = true
    private var rampExtreme: Int? = null
    // Readings one level against the ramp, counted per new sample: a single boundary blip is not a knob move.
    private var againstSamples = 0
    private var againstSampleTime = Long.MIN_VALUE
    private var rampTime = 0L
    private var rampOffSince = 0L
    private var steadyResistance = -1
    private var steadySince = 0L
    // Cadence smoothed over a few seconds: the rider's cadence wobbles right at an interval
    // change, and one rpm moves the modeled level by up to a whole step near 350 W.
    private var cadenceAverage: Float? = null
    private var cadenceAverageTime = 0L
    // Since when the rider has been continuously below the model's range (but still pedaling):
    // after LOW_CADENCE_FORGET_MS the old average no longer describes them.
    private var belowModelSince: Long? = null
    // Set when that average was forgotten: the next rise into the model's range is a fresh spin-up.
    private var cadenceForgotten = false
    private val cadenceHistory = CadenceHistory()
    // When the rider (re)started pedaling. While they spin up the cadence average is still low:
    // on Oct 31 a jump sized for a 55 rpm average put out ~285 W at 75 rpm. So a request for more
    // power waits at the current level until SPIN_UP_MS of pedaling, then jumps for the real cadence.
    // Pedaling starts at PEDALING_START_RPM and only stops below PEDALING_STOP_RPM, so a dip or
    // low-cadence riding does not restart the window.
    private var pedaling = false
    private var pedalingSince = 0L
    private var waitingTarget: Int? = null
    private var waitSince = 0L
    private var targetChangedAt = 0L

    /**
     * Debug trace of what ERG control sees and decides: controller commands, target changes
     * (with the cadence and modeled level used), ramp/settle progress, every resistance write
     * with its reason, stops, and a 1 s snapshot while a mode is active. Null (the default)
     * costs nothing: Kotlin skips building the message on a null safe call.
     */
    @Volatile var trace: ((String) -> Unit)? = null
    private var lastSnapshot = 0L
    // Last feedback (trim) values, for the trace; cleared while a modeled jump owns the level.
    private var lastFiltered: Float? = null
    private var lastError: Float? = null
    private var lastBand: Float? = null

    @Synchronized fun useExternalPower(reading: com.spop.poverlay.sensor.power.ExternalPowerReading?) {
        val next = reading?.takeIf { com.spop.poverlay.sensor.power.freshExternalPower(it, clock()) != null }
        if (externalPower?.address != next?.address) {
            if (state.value.mode == ControlMode.Erg || pausedMode == ControlMode.Erg)
                stop("Power source changed · restart ERG")
            filteredPower = null
        }
        externalPower = next
    }

    @Synchronized fun tune(shiftSize: Int, gain: Float,
        wattsPerShift: Int = state.value.wattsPerShift,
        inclineSensitivity: Float = state.value.inclineSensitivity,
        rampRate: Int = state.value.rampRate) {
        mutableState.value = state.value.copy(shiftSize = shiftSize.coerceIn(1, 10),
            rampRate = rampRate.coerceIn(RAMP_RATE_MIN, RAMP_RATE_MAX),
            wattsPerShift = wattsPerShift.coerceIn(1, 50),
            inclineSensitivity = if (inclineSensitivity.isFinite()) inclineSensitivity.coerceIn(0f, 5f) else 2f,
            gain = if (gain.isFinite()) gain.coerceIn(.001f, .03f) else .007f)
    }

    @Synchronized fun acceptSample(value: BikeSample?) {
        if (!supported) return
        if (value == null || !value.power.isFinite() || value.power < 0 ||
            !value.cadence.isFinite() || value.cadence < 0 || value.resistance !in 0..100) {
            sample = null
            unavailable("Bike+ disconnected")
            return
        }
        sample = value
        if (clock() - value.timestamp !in 0..1500) { unavailable("Bike+ data is stale"); return }
        mutableState.value = state.value.copy(connected = true,
            targetResistance = if (state.value.mode == ControlMode.Manual) value.resistance else state.value.targetResistance,
            message = if (!state.value.connected) "Ready" else state.value.message)
    }

    private fun fresh(): Boolean = supported && sample?.let { clock() - it.timestamp in 0..1500 } == true
    private fun unavailable(reason: String) {
        stop(reason)
        // After a gap the rider may have stopped: spin-up protection applies again on reconnect.
        pedaling = false
        cadenceAverage = null
        mutableState.value = state.value.copy(connected = false)
    }
    @Synchronized fun stop(reason: String = "Manual control", releaseControl: Boolean = true) {
        if (state.value.mode != ControlMode.Manual || pausedMode != null)
            trace?.invoke("stop reason=\"$reason\" mode=${state.value.mode} level=${sample?.resistance}")
        val lostPermission = releaseControl && owner != null
        if (releaseControl) owner = null
        pausedMode = null
        expectedResistance = null
        filteredPower = null
        clearFeedForward()
        mutableState.value = state.value.copy(mode = ControlMode.Manual, shiftOffset = 0, message = reason, externalControl = null,
            targetResistance = sample?.resistance ?: state.value.targetResistance,
            permissionLostCount = state.value.permissionLostCount + if (lostPermission) 1 else 0)
    }
    @Synchronized fun disconnect(client: String) { if (owner == client) stop("Controller disconnected") }
    @Synchronized fun disconnectTransport(prefix: String) { if (owner?.startsWith(prefix) == true) stop("Controller disconnected") }

    private fun setMode(mode: ControlMode) {
        pausedMode = null
        if (state.value.mode != mode) {
            trace?.invoke("mode ${state.value.mode}->$mode level=${sample!!.resistance}")
            requestedResistance = sample!!.resistance.toFloat()
            expectedResistance = sample!!.resistance
            commandTime = clock()
            previousTick = clock()
            filteredPower = null
            simBase = requestedResistance
            feedForwardTarget = null
            feedForwardUntil = 0L
            feedForwardArrivalPending = false
            rampLevel = null
            mutableState.value = state.value.copy(mode = mode, shiftOffset = 0)
        }
        mutableState.value = state.value.copy(message = "${mode.name} active")
    }
    @Synchronized fun localErg(watts: Int): Boolean {
        if (!fresh() || watts !in 25..1000) return false
        setMode(ControlMode.Erg)
        mutableState.value = state.value.copy(targetWatts = watts, externalControl = null)
        return true
    }
    @Synchronized fun localSimulation(grade: Float = state.value.targetIncline): Boolean {
        if (!fresh() || !grade.isFinite() || grade !in -327.68f..327.67f) return false
        simulation = Simulation(0f, grade, .004f, .51f)
        setMode(ControlMode.Simulation)
        mutableState.value = state.value.copy(targetIncline = grade, externalControl = null)
        return true
    }
    // Local adjustments do not acquire or revoke the remote FTMS lease. The next
    // command from its owner wins, including while the settings dialog is open.
    @Synchronized fun localManual(): Boolean {
        if (!fresh()) return false
        return localResistance(sample!!.resistance)
    }
    @Synchronized fun localResistance(level: Int): Boolean {
        if (!fresh() || level !in 0..100) return false
        setMode(ControlMode.Resistance)
        mutableState.value = state.value.copy(targetResistance = level, message = "Manual active", externalControl = null)
        return true
    }
    @Synchronized fun shift(direction: Int) {
        if (!fresh()) return
        val step = direction.coerceIn(-1, 1)
        if (step == 0) return
        when (state.value.mode) {
            ControlMode.Erg -> { localErg((state.value.targetWatts + step * state.value.wattsPerShift).coerceIn(25, 1000)); return }
            ControlMode.Manual, ControlMode.Resistance -> {
                val base = if (state.value.mode == ControlMode.Manual) sample!!.resistance else state.value.targetResistance
                localResistance((base + step * state.value.shiftSize).coerceIn(0, 100)); return
            }
            ControlMode.Simulation -> Unit
        }
        val base = simBase + simulation.resistanceOffset(state.value.inclineSensitivity)
        val current = (base + state.value.shiftOffset).coerceIn(0f, 100f)
        val next = (current + direction.coerceIn(-1, 1) * state.value.shiftSize).coerceIn(0f, 100f)
        mutableState.value = state.value.copy(shiftOffset = (next - base).roundToInt(), externalControl = null)
    }

    /** Called at 100 ms; only new telemetry drives feedback, except an active ramp. Stale data disarms control. */
    private var lastSampleTime = Long.MIN_VALUE
    @Synchronized fun tick() {
        if (externalPower != null && com.spop.poverlay.sensor.power.freshExternalPower(externalPower, clock()) == null)
            useExternalPower(null)
        if (!fresh()) { if (state.value.connected) unavailable("Bike+ data is stale"); return }
        val reading = sample ?: return
        val newSample = reading.timestamp != lastSampleTime
        if (!newSample && rampLevel == null) return
        if (newSample) {
            lastSampleTime = reading.timestamp
            learnPowerModel(reading)
            averageCadence(reading)
            cadenceHistory.record(reading.timestamp, reading.cadence)
        }
        if (state.value.mode == ControlMode.Manual) return
        if (newSample && clock() - lastSnapshot >= SNAPSHOT_MS) {
            lastSnapshot = clock()
            trace?.invoke("snap mode=${state.value.mode} target=${state.value.targetWatts} power=${f1(reading.power)}" +
                " ext=${externalPower?.watts} filt=${lastFiltered?.let(::f1)} cad=${f1(reading.cadence)}" +
                " cadAvg=${cadenceAverage?.let(::f1)} level=${reading.resistance} req=${f1(requestedResistance)}" +
                " err=${lastError?.let(::f1)} band=${lastBand?.let(::f1)} ff=${feedForwardActive()} ramp=${rampLevel?.let(::f1)}")
        }
        if (rampLevel != null) { rampTick(reading); return }
        val dt = ((clock() - previousTick) / 1000f).coerceIn(0f, .4f)
        previousTick = clock()
        val expected = expectedResistance
        if (expected != null && reading.resistance != expected) {
            // Allow the binder target echo to catch up. Do not keep moving the motor
            // against a knob override or a mainboard that ignored a write.
            if (clock() - commandTime > ackTimeoutMs) stop("Resistance changed manually or command not acknowledged")
            return
        }
        if (reading.cadence < PEDALING_STOP_RPM) {
            filteredPower = null
            requestedResistance = reading.resistance.toFloat()
            // A stop mid-ramp drops the expectation (the stopped bike may never finish the last
            // write). Once the reading has been still for the acknowledgement window, expect it
            // again, so a knob turned while stopped is still caught as a manual override.
            if (expectedResistance == null && clock() - steadySince >= ACK_TIMEOUT_MS) {
                expectedResistance = reading.resistance
                commandTime = clock()
                ackTimeoutMs = ACK_TIMEOUT_MS
            }
            // A stop ends any jump or settle hold: on restart the target is judged afresh,
            // with spin-up protection, rather than resuming the old jump.
            if (feedForwardTarget != null || feedForwardActive() || waitingTarget != null) clearFeedForward()
            return
        }
        val target = when (state.value.mode) {
            ControlMode.Erg -> {
                val external = com.spop.poverlay.sensor.power.freshExternalPower(externalPower, clock())
                val measured = external ?: reading.power
                val targetWatts = state.value.targetWatts
                // The wait is bounded: a rider who never reaches PEDALING_START_RPM still gets
                // feedback after SPIN_UP_MS instead of holding forever.
                if (feedForwardTarget != targetWatts && external == null && spinningUp() &&
                    targetWatts - measured >= FEED_FORWARD_MIN_WATTS &&
                    (waitingTarget != targetWatts || clock() - waitSince < SPIN_UP_MS)) {
                    if (waitingTarget != targetWatts) {
                        waitingTarget = targetWatts
                        waitSince = clock()
                        trace?.invoke("wait spin-up target=$targetWatts measured=${f1(measured)} level=${reading.resistance}" +
                            " cad=${f1(reading.cadence)} cadAvg=${cadenceAverage?.let(::f1)}" +
                            " pedaling=${if (!pedaling) "no" else "${clock() - pedalingSince}ms"}")
                    }
                    filteredPower = null
                    requestedResistance = reading.resistance.toFloat()
                    return
                }
                waitingTarget = null
                if (feedForwardTarget != targetWatts) {
                    val previousTarget = feedForwardTarget
                    feedForwardTarget = targetWatts
                    targetChangedAt = clock()
                    // Drops use the cadence held at about the new target (or the rider's typical
                    // cadence): riders settle back from a hard effort's higher cadence within seconds.
                    // The remembered cadence is floored at the current average minus DROP_CADENCE_ALLOWANCE:
                    // that covers the post-effort settle (97 -> 89 rpm) but a remembered slow cadence
                    // (a 60 rpm warm-up) cannot size a heavy "drop" for a rider now at 95 rpm.
                    // Only a real easing-off is a drop: a lower target that is still above the measured power
                    // (320 -> 300 W while the 150 -> 320 W ramp is in flight) is still a rise.
                    val drop = previousTarget != null && targetWatts < previousTarget && measured > targetWatts
                    val held = if (drop) cadenceHistory.cadenceAt(targetWatts, clock()) else null
                    val typical = if (drop && held == null) cadenceHistory.typicalCadence(clock()) else null
                    val remembered = held ?: typical
                    val current = cadenceAverage ?: reading.cadence
                    val floor = current - DROP_CADENCE_ALLOWANCE
                    val cadence = if (drop && remembered != null) maxOf(remembered, floor) else current
                    val cadenceSource = when {
                        drop && remembered != null && floor > remembered -> "floor"
                        held != null -> "held"
                        typical != null -> "median2m"
                        cadenceAverage != null -> "avg5s"
                        else -> "reading"
                    }
                    // After spin-up, a rider still below the model's range (50 rpm) gets plain feedback.
                    val lowCadence = cadenceAverage.let { it == null || it < MODEL_MIN_CADENCE }
                    // Only large requests jump; small ones keep the existing smooth trim.
                    // The model predicts Bike+ native power, so an external meter keeps pure feedback.
                    // Beyond the fitted range the model is unreliable: jump no higher than
                    // FEED_FORWARD_MAX_LEVEL and let ordinary feedback climb the rest.
                    // A drop, or any request while spinning up (only easing off gets here then), never adds resistance.
                    val modeled = if (external == null && !lowCadence && abs(targetWatts - measured) >= FEED_FORWARD_MIN_WATTS)
                        powerModel.nearestLevelFor(targetWatts.toFloat(), cadence)?.coerceAtMost(FEED_FORWARD_MAX_LEVEL)
                            ?.let { if (drop || spinningUp()) minOf(it, reading.resistance) else it } else null
                    // A "jump" to the current level is no jump: no settle hold, ordinary feedback takes it.
                    val level = modeled?.takeIf { it != reading.resistance }
                    trace?.invoke("target from=$previousTarget to=$targetWatts measured=${f1(measured)}" +
                        " ext=${external != null} level=${reading.resistance} cad=${f1(reading.cadence)}" +
                        " cadAvg=${cadenceAverage?.let(::f1)} cadUsed=${f1(cadence)} cadSrc=$cadenceSource" +
                        " action=" + when {
                            level != null -> "jump to=$level rate=${state.value.rampRate}/s"
                            modeled != null -> "trim(at level)"
                            external != null -> "trim(external power)"
                            lowCadence -> "trim(low cadence)"
                            abs(targetWatts - measured) < FEED_FORWARD_MIN_WATTS -> "trim(small change)"
                            else -> "trim(no model)"
                        })
                    if (level != null) {
                        feedForwardLevel = level.toFloat()
                        feedForwardUntil = clock() + FEED_FORWARD_SETTLE_MS
                        feedForwardArrivalPending = true
                        feedForwardDeadline = clock() + FEED_FORWARD_MAX_MS
                        filteredPower = null
                        lastFiltered = null; lastError = null; lastBand = null
                        rampLevel = reading.resistance.toFloat()
                        rampUp = level >= reading.resistance
                        rampExtreme = null
                        againstSamples = 0
                        rampTime = clock() - RAMP_TICK_MS
                        rampOffSince = 0L
                        rampTick(reading)
                        return
                    } else {
                        feedForwardUntil = 0L
                        feedForwardArrivalPending = false
                        feedForwardDeadline = 0L
                    }
                }
                if (feedForwardArrivalPending && reading.resistance == feedForwardLevel.roundToInt()) {
                    trace?.invoke("arrive level=${reading.resistance} after=${clock() - targetChangedAt}ms power=${f1(measured)}" +
                        " cad=${f1(reading.cadence)} settle=${FEED_FORWARD_SETTLE_MS}ms")
                    feedForwardArrivalPending = false
                    feedForwardUntil = maxOf(feedForwardUntil, clock() + FEED_FORWARD_SETTLE_MS)
                }
                if (!feedForwardActive() && clock() - targetChangedAt >= STEADY_AFTER_CHANGE_MS && abs(measured - targetWatts) <= 7f)
                    cadenceHistory.recordSteady(reading.timestamp, reading.cadence, targetWatts)
                if (feedForwardActive()) feedForwardLevel
                else {
                    val power = filteredPower?.let { it + dt / (2f + dt) * (measured - it) } ?: measured
                    filteredPower = power
                    val error = targetWatts - power
                    // Hold within half a resistance level (at least 3 W): one level is about
                    // 6 W near 160 W but 13 W near 345 W, so a fixed 3 W band hunted at high power.
                    val level = requestedResistance.roundToInt().coerceIn(0, 99)
                    val cadence = cadenceAverage ?: reading.cadence
                    val band = maxOf(MIN_DEADBAND_WATTS,
                        DEADBAND_LEVEL_FRACTION * (powerModel.wattsAt(level + 1, cadence) - powerModel.wattsAt(level, cadence)))
                    lastFiltered = power
                    lastError = error
                    lastBand = band
                    requestedResistance + if (abs(error) <= band) 0f else state.value.gain * error * dt / .1f
                }
            }
            ControlMode.Simulation -> simBase + simulation.resistanceOffset(state.value.inclineSensitivity) + state.value.shiftOffset
            ControlMode.Resistance -> state.value.targetResistance.toFloat()
            else -> return
        }
        // Limit every mode outside a modeled ramp to 3 Peloton resistance points / second,
        // including mode changes. Modeled jumps travel only through rampTick() at the rider's
        // ramp speed; during the settle hold that follows, the level is already at its goal.
        val jump = state.value.mode == ControlMode.Erg && feedForwardActive()
        requestedResistance = target.coerceIn(requestedResistance - RESISTANCE_RATE * dt, requestedResistance + RESISTANCE_RATE * dt).coerceIn(0f, 100f)
        val next = requestedResistance.roundToInt()
        if (next != reading.resistance) {
            trace?.invoke("write level=$next from=${reading.resistance} why=" + when {
                jump -> "settle"
                state.value.mode == ControlMode.Erg -> "trim err=${lastError?.let(::f1)} band=${lastBand?.let(::f1)} filt=${lastFiltered?.let(::f1)}"
                else -> state.value.mode.name.lowercase()
            })
            if (!writeResistance(next)) { stop("Resistance command failed"); return }
            ackTimeoutMs = ACK_TIMEOUT_MS + if (jump) abs(next - reading.resistance) * ACK_MS_PER_LEVEL else 0L
            expectedResistance = next
            commandTime = clock()
        }
    }

    /**
     * Advance the ramp toward the modeled level at RAMP_RATE. The binder echo may trail the
     * ramp by a few levels; staying further off it for the acknowledgement window means a
     * knob override or ignored writes, so control stops as it does for single writes.
     */
    private fun rampTick(reading: BikeSample) {
        val ramp = rampLevel ?: return
        val goal = feedForwardLevel.roundToInt()
        if (state.value.mode != ControlMode.Erg || state.value.targetWatts != feedForwardTarget || reading.cadence < PEDALING_STOP_RPM) {
            trace?.invoke("ramp cancelled at=${reading.resistance} goal=$goal mode=${state.value.mode}" +
                " target=${state.value.targetWatts} cad=${f1(reading.cadence)}")
            // Nothing of the cancelled jump may survive: no settle hold aimed at its goal.
            // A changed target keeps feedForwardTarget so the next tick still sees the drop/rise.
            val targetChanged = state.value.mode == ControlMode.Erg && state.value.targetWatts != feedForwardTarget
            val keep = feedForwardTarget
            clearFeedForward()
            if (targetChanged) feedForwardTarget = keep
            // The last ramp write is usually ahead of the echo: keep expecting it (with time to arrive)
            // rather than the lagging reading, or the arriving echo would look like a knob override.
            // A real knob move never reaches the written level, so it still times out and stops.
            if (reading.cadence < PEDALING_STOP_RPM) {
                // Stopped mid-ramp: the bike may not finish the last write while nobody pedals, so
                // expect nothing; the next write after the rider restarts sets a fresh expectation.
                requestedResistance = reading.resistance.toFloat()
                expectedResistance = null
                commandTime = clock()
                previousTick = clock()
                return
            }
            val written = expectedResistance ?: reading.resistance
            requestedResistance = written.toFloat()
            expectedResistance = written
            ackTimeoutMs = ACK_TIMEOUT_MS + abs(written - reading.resistance) * ACK_MS_PER_LEVEL
            commandTime = clock()
            previousTick = clock()
            return
        }
        // The echo trails the ramp but never runs against it: a reading that falls back from the
        // furthest level reached (or rises, on a downward ramp) is the knob, so stop, whatever the size.
        rampExtreme = rampExtreme?.let { if (rampUp) maxOf(it, reading.resistance) else minOf(it, reading.resistance) }
            ?: reading.resistance
        val against = if (rampUp) rampExtreme!! - reading.resistance else reading.resistance - rampExtreme!!
        if (against <= 0) againstSamples = 0
        else if (reading.timestamp != againstSampleTime) { againstSamples++; againstSampleTime = reading.timestamp }
        // Two levels back at once, or one level back on two samples in a row (~400 ms): a single
        // one-level blip at a level boundary is not treated as a knob move.
        if (against >= KNOB_AGAINST_RAMP_LEVELS || (against >= 1 && againstSamples >= KNOB_AGAINST_RAMP_SAMPLES)) {
            trace?.invoke("ramp knob override level=${reading.resistance} extreme=$rampExtreme goal=$goal")
            stop("Resistance changed manually")
            return
        }
        if (against >= 1) {
            // Hold the ramp until the next reading: with no write in between, a knob turn stays
            // visible and is confirmed, while a boundary blip clears by itself.
            rampTime = clock()
            return
        }
        val expected = expectedResistance
        if (expected != null && abs(reading.resistance - expected) > RAMP_ECHO_LAG_LEVELS) {
            // Pause rather than keep writing against a knob that has been moved.
            if (rampOffSince == 0L) rampOffSince = clock()
            else if (clock() - rampOffSince > ACK_TIMEOUT_MS) stop("Resistance changed manually or command not acknowledged")
            rampTime = clock()
            return
        }
        rampOffSince = 0L
        val seconds = ((clock() - rampTime) / 1000f).coerceIn(0f, .2f)
        rampTime = clock()
        val step = state.value.rampRate * seconds
        val next = if (goal > ramp) minOf(ramp + step, goal.toFloat()) else maxOf(ramp - step, goal.toFloat())
        rampLevel = next
        val level = next.roundToInt()
        if (level != expectedResistance && level != reading.resistance) {
            trace?.invoke("write level=$level from=${reading.resistance} why=ramp goal=$goal")
            if (!writeResistance(level)) { stop("Resistance command failed"); return }
            expectedResistance = level
            commandTime = clock()
            ackTimeoutMs = ACK_TIMEOUT_MS + RAMP_ECHO_LAG_LEVELS * ACK_MS_PER_LEVEL
        }
        requestedResistance = level.toFloat()
        if (level == goal) {
            trace?.invoke("ramp done level=$level after=${clock() - targetChangedAt}ms")
            rampLevel = null
            rampExtreme = null
            previousTick = clock()
            // A slow ramp (3 levels/s) may use up the arrival deadline; give the bike its usual time.
            feedForwardDeadline = maxOf(feedForwardDeadline, clock() + ARRIVAL_AFTER_RAMP_MS)
        }
    }

    private fun spinningUp(): Boolean = !pedaling || clock() - pedalingSince < SPIN_UP_MS

    /** Forget any modeled jump: its ramp, settle hold and arrival wait. */
    private fun clearFeedForward() {
        feedForwardTarget = null
        feedForwardUntil = 0L
        feedForwardArrivalPending = false
        feedForwardDeadline = 0L
        rampLevel = null
        rampExtreme = null
        againstSamples = 0
        // A stop (or anything else that drops the jump) also ends a spin-up wait: the next restart waits afresh.
        waitingTarget = null
        waitSince = 0L
    }

    private fun feedForwardActive(): Boolean =
        clock() < feedForwardUntil || (feedForwardArrivalPending && clock() < feedForwardDeadline)

    private fun averageCadence(reading: BikeSample) {
        if (!pedaling && reading.cadence >= PEDALING_START_RPM) { pedaling = true; pedalingSince = clock() }
        if (reading.cadence < PEDALING_STOP_RPM) pedaling = false
        val previous = cadenceAverage
        val seconds = ((reading.timestamp - cadenceAverageTime) / 1000f).coerceIn(0f, 1f)
        cadenceAverageTime = reading.timestamp
        belowModelSince = if (reading.cadence >= MODEL_MIN_CADENCE || reading.cadence < PEDALING_STOP_RPM) null
            else belowModelSince ?: clock()
        val forgotten = belowModelSince?.let { clock() - it >= LOW_CADENCE_FORGET_MS } == true
        if (forgotten) cadenceForgotten = true
        // After a long low-cadence stretch the rider is spinning up again: restart the spin-up window
        // so a jump is not sized from the first sample above 50 rpm (Fable review: 55 rpm -> level 75).
        if (cadenceForgotten && pedaling && reading.cadence >= MODEL_MIN_CADENCE) {
            cadenceForgotten = false
            pedalingSince = clock()
        }
        if (reading.cadence < PEDALING_STOP_RPM) cadenceForgotten = false
        cadenceAverage = when {
            reading.cadence < PEDALING_STOP_RPM -> null
            forgotten -> null
            // The power model is only valid from 50 rpm: slower samples neither feed nor reset the average.
            reading.cadence < MODEL_MIN_CADENCE -> previous
            previous == null -> reading.cadence
            else -> previous + seconds / (CADENCE_AVERAGE_S + seconds) * (reading.cadence - previous)
        }
    }

    /** Learn watts per level from readings taken after the resistance has been still for a while. */
    private fun learnPowerModel(reading: BikeSample) {
        if (reading.resistance != steadyResistance) {
            steadyResistance = reading.resistance
            steadySince = clock()
            return
        }
        if (clock() - steadySince >= LEARN_SETTLE_MS) powerModel.learn(reading.resistance, reading.power, reading.cadence)
    }

    companion object {
        const val RESISTANCE_RATE = 3f
        // Highest level a modeled jump may target; the fit covers levels 18-63.
        const val FEED_FORWARD_MAX_LEVEL = 75
        // Modeled jumps ramp at 10 levels/s by default, one level per 100 ms tick (43% -> 58% in 1.5 s).
        // How abrupt a jump should feel is a rider preference, so the rate is adjustable (3-20 levels/s).
        const val RAMP_RATE_DEFAULT = 10
        const val RAMP_RATE_MIN = 3
        const val RAMP_RATE_MAX = 20
        const val RAMP_TICK_MS = 100L
        const val RAMP_ECHO_LAG_LEVELS = 5
        // ERG holds within half a resistance level's watts, never less than 3 W.
        const val DEADBAND_LEVEL_FRACTION = .5f
        const val MIN_DEADBAND_WATTS = 3f
        const val CADENCE_AVERAGE_S = 5f
        const val STEADY_AFTER_CHANGE_MS = 8000L
        const val ACK_TIMEOUT_MS = 1500L
        const val ACK_MS_PER_LEVEL = 100L
        const val FEED_FORWARD_MIN_WATTS = 15
        // Hold after arrival while the rider's cadence and the load-cell power settle.
        const val FEED_FORWARD_SETTLE_MS = 4500L
        const val FEED_FORWARD_MAX_MS = 10_000L
        const val LEARN_SETTLE_MS = 2500L
        const val SNAPSHOT_MS = 1000L
        // No modeled jump until the rider has pedaled (40+ rpm) this long: their cadence is still rising.
        const val SPIN_UP_MS = 15_000L
        const val PEDALING_START_RPM = 40f
        const val PEDALING_STOP_RPM = 25f
        const val MODEL_MIN_CADENCE = 50f
        // A drop's remembered cadence may sit at most this far below the current average.
        const val DROP_CADENCE_ALLOWANCE = 8f
        // A reading this far back against the ramp direction is a knob move, not echo lag: the echo
        // trails but never reverses (0 reversals in 26 ramps on the Oct 31 rides). One level back
        // counts only when it persists for KNOB_AGAINST_RAMP_SAMPLES samples (a boundary blip does not).
        const val KNOB_AGAINST_RAMP_LEVELS = 2
        const val KNOB_AGAINST_RAMP_SAMPLES = 2
        // Continuously below 50 rpm (still pedaling) this long: forget the old cadence average.
        const val LOW_CADENCE_FORGET_MS = 30_000L
        // After a ramp ends, wait this long for the bike to report the goal before falling back to feedback.
        const val ARRIVAL_AFTER_RAMP_MS = 8_500L
        private fun f1(value: Float) = (value * 10f).roundToInt() / 10f
    }

    /** FTMS v1.0.1 control point. Returns the protocol result and optional Machine Status. */
    data class Reply(val result: Int, val status: ByteArray? = null)
    @Synchronized fun procedure(client: String, bytes: ByteArray): Reply {
        val reply = runProcedure(client, bytes)
        trace?.invoke("cmd client=$client bytes=${bytes.joinToString(" ") { "%02X".format(it) }} " +
            describeCommand(bytes) + " result=${reply.result} mode=${state.value.mode}")
        return reply
    }

    private fun describeCommand(bytes: ByteArray): String {
        fun signed(index: Int) = if (bytes.size < index + 2) null
            else ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
        return when (bytes.firstOrNull()?.toInt()?.and(255)) {
            0 -> "op=request-control"
            1 -> "op=reset"
            4 -> "op=set-resistance level=${signed(1)?.let { it / 10f }}"
            5 -> "op=set-power watts=${signed(1)}"
            7 -> "op=start-resume"
            8 -> "op=stop-pause param=${bytes.getOrNull(1)}"
            0x11 -> "op=sim grade=${signed(3)?.let { it / 100f }} wind=${signed(1)?.let { it / 1000f }}"
            null -> "op=empty"
            else -> "op=${"%02X".format(bytes[0])}"
        }
    }

    private fun runProcedure(client: String, bytes: ByteArray): Reply {
        val op = bytes.firstOrNull()?.toInt()?.and(255) ?: return Reply(3)
        val size = when (op) { 0, 1, 7 -> 1; 8 -> 2; 4, 5 -> 3; 0x11 -> 7; else -> return Reply(2) }
        if (bytes.size != size) return Reply(3)
        if (!supported) return Reply(2)
        if (!fresh()) return Reply(4)
        if (op == 0) {
            if (owner != null && owner != client) return Reply(5)
            owner = client
            return Reply(1)
        }
        if (owner != client) return Reply(5)
        fun signed(index: Int) = ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort().toInt()
        val reply = when (op) {
            1 -> { stop("Reset", releaseControl = false); Reply(1, byteArrayOf(1)) }
            7 -> {
                pausedMode?.let { mode ->
                    requestedResistance = sample!!.resistance.toFloat()
                    expectedResistance = sample!!.resistance
                    previousTick = clock()
                    commandTime = clock()
                    mutableState.value = state.value.copy(mode = mode, shiftOffset = pausedShift,
                        targetResistance = pausedResistance, message = "${mode.name} active")
                }
                pausedMode = null
                Reply(1, byteArrayOf(4))
            }
            8 -> {
                if (bytes[1].toInt() !in 1..2) Reply(3)
                else {
                    val mode = state.value.mode
                    val shift = state.value.shiftOffset
                    val resistance = state.value.targetResistance
                    stop(if (bytes[1].toInt() == 2) "Paused" else "Stopped", releaseControl = false)
                    if (bytes[1].toInt() == 2) { pausedMode = mode; pausedShift = shift; pausedResistance = resistance }
                    Reply(1, byteArrayOf(2, bytes[1]))
                }
            }
            4 -> {
                // Corrected FTMS format: SINT16 in tenths (FTMS TS p6 BV-25-C).
                val level = signed(1)
                if (level !in 0..1000 || level % 10 != 0) Reply(3) else {
                    setMode(ControlMode.Resistance)
                    mutableState.value = state.value.copy(targetResistance = level / 10, message = "Manual active")
                    Reply(1, byteArrayOf(7) + bytes.copyOfRange(1, 3))
                }
            }
            5 -> {
                val watts = signed(1)
                if (watts !in 25..1000) Reply(3) else {
                    setMode(ControlMode.Erg)
                    mutableState.value = state.value.copy(targetWatts = watts)
                    Reply(1, byteArrayOf(8) + bytes.copyOfRange(1, 3))
                }
            }
            else -> {
                simulation = Simulation(signed(1) * .001f, signed(3) * .01f,
                    (bytes[5].toInt() and 255) * .0001f, (bytes[6].toInt() and 255) * .01f)
                setMode(ControlMode.Simulation)
                mutableState.value = state.value.copy(targetIncline = simulation.grade)
                Reply(1, byteArrayOf(0x12) + bytes.copyOfRange(1, 7))
            }
        }
        if (reply.result == 1 && (op == 4 || op == 5 || op == 0x11 || (op == 7 && state.value.mode != ControlMode.Manual))) {
            mutableState.value = state.value.copy(externalControl = if (client.startsWith("dircon:")) "DirCon" else "Bluetooth")
        }
        return reply
    }
}
