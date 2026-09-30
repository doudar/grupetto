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
    val shiftOffset: Int = 0,
    val shiftSize: Int = 2,
    val gain: Float = .007f,
    val permissionLostCount: Int = 0,
    val message: String = "Waiting for Bike+"
)
data class Simulation(val wind: Float, val grade: Float, val rolling: Float, val drag: Float) {
    // A repeatable resistance model: 2 Peloton points per percent grade, with wind and
    // rolling resistance expressed as equivalent grade at 30 km/h and 85 kg total mass.
    // This is a trainer feel model, not a claim of calibrated road power or rider mass.
    fun resistanceOffset(): Float {
        val airSpeed = 30f / 3.6f + wind
        val extraForce = drag * airSpeed * abs(airSpeed) - .51f * (30f / 3.6f) * (30f / 3.6f)
        return 2f * (grade + (rolling - .004f) * 100f + extraForce / (85f * 9.80665f) * 100f)
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
    private var previousTick = 0L
    private var simBase = 0f
    private var simulation = Simulation(0f, 0f, .004f, .51f)
    private var resistanceTarget = 0
    private var pausedMode: ControlMode? = null
    private var pausedShift = 0
    private var externalPower: com.spop.poverlay.sensor.power.ExternalPowerReading? = null

    @Synchronized fun useExternalPower(reading: com.spop.poverlay.sensor.power.ExternalPowerReading?) {
        val next = reading?.takeIf { com.spop.poverlay.sensor.power.freshExternalPower(it, clock()) != null }
        if (externalPower?.address != next?.address) {
            if (state.value.mode == ControlMode.Erg || pausedMode == ControlMode.Erg)
                stop("Power source changed · restart ERG")
            filteredPower = null
        }
        externalPower = next
    }

    @Synchronized fun tune(shiftSize: Int, gain: Float) {
        mutableState.value = state.value.copy(shiftSize = shiftSize.coerceIn(1, 10),
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
            message = if (!state.value.connected) "Ready" else state.value.message)
    }

    private fun fresh(): Boolean = supported && sample?.let { clock() - it.timestamp in 0..1500 } == true
    private fun unavailable(reason: String) {
        stop(reason)
        mutableState.value = state.value.copy(connected = false)
    }
    @Synchronized fun stop(reason: String = "Manual control", releaseControl: Boolean = true) {
        val lostPermission = releaseControl && owner != null
        if (releaseControl) owner = null
        pausedMode = null
        expectedResistance = null
        filteredPower = null
        mutableState.value = state.value.copy(mode = ControlMode.Manual, shiftOffset = 0, message = reason,
            permissionLostCount = state.value.permissionLostCount + if (lostPermission) 1 else 0)
    }
    @Synchronized fun disconnect(client: String) { if (owner == client) stop("Controller disconnected") }
    @Synchronized fun disconnectTransport(prefix: String) { if (owner?.startsWith(prefix) == true) stop("Controller disconnected") }

    private fun setMode(mode: ControlMode) {
        pausedMode = null
        if (state.value.mode != mode) {
            requestedResistance = sample!!.resistance.toFloat()
            expectedResistance = sample!!.resistance
            commandTime = clock()
            previousTick = clock()
            filteredPower = null
            simBase = requestedResistance
            mutableState.value = state.value.copy(mode = mode, shiftOffset = 0)
        }
        mutableState.value = state.value.copy(message = "${mode.name} active")
    }
    @Synchronized fun localErg(watts: Int): Boolean {
        if (!fresh() || watts !in 25..1000 || (owner != null && owner != "local")) return false
        owner = "local"
        setMode(ControlMode.Erg)
        mutableState.value = state.value.copy(targetWatts = watts)
        return true
    }
    @Synchronized fun localSimulation(): Boolean {
        if (!fresh() || (owner != null && owner != "local")) return false
        owner = "local"
        simulation = Simulation(0f, 0f, .004f, .51f)
        setMode(ControlMode.Simulation)
        return true
    }
    @Synchronized fun shift(direction: Int) {
        if (!fresh() || state.value.mode != ControlMode.Simulation) return
        val base = simBase + simulation.resistanceOffset()
        val current = (base + state.value.shiftOffset).coerceIn(0f, 100f)
        val next = (current + direction.coerceIn(-1, 1) * state.value.shiftSize).coerceIn(0f, 100f)
        mutableState.value = state.value.copy(shiftOffset = (next - base).roundToInt())
    }

    /** Called at 100 ms; only new telemetry drives feedback. Stale data disarms control. */
    private var lastSampleTime = Long.MIN_VALUE
    @Synchronized fun tick() {
        if (externalPower != null && com.spop.poverlay.sensor.power.freshExternalPower(externalPower, clock()) == null)
            useExternalPower(null)
        if (!fresh()) { if (state.value.connected) unavailable("Bike+ data is stale"); return }
        val reading = sample ?: return
        if (state.value.mode == ControlMode.Manual || reading.timestamp == lastSampleTime) return
        lastSampleTime = reading.timestamp
        val dt = ((clock() - previousTick) / 1000f).coerceIn(0f, .4f)
        previousTick = clock()
        val expected = expectedResistance
        if (expected != null && reading.resistance != expected) {
            // Allow the binder target echo to catch up. Do not keep moving the motor
            // against a knob override or a mainboard that ignored a write.
            if (clock() - commandTime > 1500) stop("Resistance changed manually or command not acknowledged")
            return
        }
        if (reading.cadence < 25f) {
            filteredPower = null
            requestedResistance = reading.resistance.toFloat()
            return
        }
        val target = when (state.value.mode) {
            ControlMode.Erg -> {
                val measured = com.spop.poverlay.sensor.power.freshExternalPower(externalPower, clock()) ?: reading.power
                val power = filteredPower?.let { it + dt / (2f + dt) * (measured - it) } ?: measured
                filteredPower = power
                val error = state.value.targetWatts - power
                requestedResistance + if (abs(error) <= 3f) 0f else state.value.gain * error * dt / .1f
            }
            ControlMode.Simulation -> simBase + simulation.resistanceOffset() + state.value.shiftOffset
            ControlMode.Resistance -> resistanceTarget.toFloat()
            else -> return
        }
        // Limit every mode to 3 Peloton resistance points / second, including mode changes.
        requestedResistance = target.coerceIn(requestedResistance - 3f * dt, requestedResistance + 3f * dt).coerceIn(0f, 100f)
        val next = requestedResistance.roundToInt()
        if (next != reading.resistance) {
            if (!writeResistance(next)) { stop("Resistance command failed"); return }
            expectedResistance = next
            commandTime = clock()
        }
    }

    /** FTMS v1.0.1 control point. Returns the protocol result and optional Machine Status. */
    data class Reply(val result: Int, val status: ByteArray? = null)
    @Synchronized fun procedure(client: String, bytes: ByteArray): Reply {
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
        return when (op) {
            1 -> { stop("Reset", releaseControl = false); Reply(1, byteArrayOf(1)) }
            7 -> {
                pausedMode?.let { mode ->
                    requestedResistance = sample!!.resistance.toFloat()
                    expectedResistance = sample!!.resistance
                    previousTick = clock()
                    commandTime = clock()
                    mutableState.value = state.value.copy(mode = mode, shiftOffset = pausedShift, message = "${mode.name} active")
                }
                pausedMode = null
                Reply(1, byteArrayOf(4))
            }
            8 -> {
                if (bytes[1].toInt() !in 1..2) Reply(3)
                else {
                    val mode = state.value.mode
                    val shift = state.value.shiftOffset
                    stop(if (bytes[1].toInt() == 2) "Paused" else "Stopped", releaseControl = false)
                    if (bytes[1].toInt() == 2) { pausedMode = mode; pausedShift = shift }
                    Reply(1, byteArrayOf(2, bytes[1]))
                }
            }
            4 -> {
                // Corrected FTMS format: SINT16 in tenths (FTMS TS p6 BV-25-C).
                val level = signed(1)
                if (level !in 0..1000 || level % 10 != 0) Reply(3) else {
                    resistanceTarget = level / 10; setMode(ControlMode.Resistance)
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
                Reply(1, byteArrayOf(0x12) + bytes.copyOfRange(1, 7))
            }
        }
    }
}
