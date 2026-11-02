package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

class ErgStopKnobTest {
    private var now = 1000L
    private var resistance = 40
    private val writes = mutableListOf<Int>()
    private val lines = mutableListOf<String>()
    private val control = BikeControl(true, { now }) { writes.add(it); resistance = it; true }.apply {
        trace = { lines.add("[$now] $it") }
    }
    private fun watts(cadence: Float) =
        (exp(1.491016 + 0.108267 * resistance - 0.000584 * resistance * resistance) * (cadence / 90.0).pow(1.7)).toFloat()
    private fun ride(ms: Int, cadence: Float, power: Float? = null) {
        var t = 0
        while (t < ms) {
            now += 100; t += 100
            if (t % 200 == 0) control.acceptSample(BikeSample(power ?: watts(cadence), cadence, resistance, now))
            control.tick()
        }
    }
    private fun laggingBike(): BikeControl = BikeControl(true, { now }) { writes.add(it); true }.apply {
        trace = { lines.add("[$now] $it") }
    }
    private fun settle(bike: BikeControl, level: Int) =
        repeat(150) { bike.acceptSample(BikeSample(170f, 90f, level, now)); now += 100; bike.tick() }

    /** Baseline (no ramp): a knob move while stopped is detected when the rider resumes. */
    @Test fun knobMoveWhileStoppedSteadyIsDetected() {
        ride(30_000, 90f)
        assertTrue(control.localErg(150)); ride(20_000, 90f)
        ride(2_000, 0f)
        resistance -= 5
        ride(3_000, 0f)
        ride(5_000, 90f)
        assertEquals(lines.takeLast(10).joinToString("\n"), ControlMode.Manual, control.state.value.mode)
    }

    /** Stop mid-ramp, bike holds where it was, rider turns knob 5 down while stopped, resumes. */
    @Test fun knobMoveWhileStoppedMidRamp() {
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0
        repeat(200) {
            now += 100; t += 100
            if (t % 200 == 0) {
                val target = writes.lastOrNull() ?: reported
                val cad = if (t in 1000..6000) 0f else 90f
                if (t < 1000) reported = minOf(target, reported + 2)
                if (t == 3000) reported -= 5            // knob turned while stopped
                bike.acceptSample(BikeSample(if (cad == 0f) 0f else 170f, cad, reported, now))
            }
            bike.tick()
        }
        println("midramp-knob: mode=${bike.state.value.mode} msg=${bike.state.value.message} writes=$writes")
        println(lines.joinToString("\n"))
        assertEquals(ControlMode.Manual, bike.state.value.mode)
    }

    /** An echo that alternates between the furthest level and one below must not stall the ramp forever. */
    @Test fun alternatingEchoDoesNotStallRamp() {
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0; var flip = false
        repeat(300) {
            now += 100; t += 100
            if (t % 200 == 0) {
                val target = writes.lastOrNull() ?: reported
                val base = minOf(target, reported + 1)
                reported = base
                val shown = if (flip && base > 44) base - 1 else base
                flip = !flip
                bike.acceptSample(BikeSample(170f, 90f, shown, now))
            }
            bike.tick()
        }
        println("alt-echo: mode=${bike.state.value.mode} msg=${bike.state.value.message} last=${writes.lastOrNull()} n=${writes.size}")
        println(lines.filter { "ramp" in it }.takeLast(5).joinToString("\n"))
        assertTrue(bike.state.value.mode == ControlMode.Manual || lines.any { "ramp done" in it })
    }

    /** Drill: 35 s at 45 rpm inside ERG, then 90 rpm; a target change 20 s later jumps at once. */
    @Test fun forgottenStretchDoesNotLinger() {
        ride(30_000, 90f)
        assertTrue(control.localErg(50)); ride(10_000, 90f)
        ride(35_000, 45f)
        ride(20_000, 90f)
        val n = writes.size
        assertTrue(control.localErg(300))
        ride(2_000, 90f)
        assertFalse(lines.any { "wait spin-up target=300" in it })
        assertTrue(lines.any { "to=300" in it && "jump" in it })
    }

    /** Drill: 35 s at 45 rpm, then a target change 1 s after rising: waits (<= 15 s), then jumps near ideal. */
    @Test fun forgottenStretchThenRiseWaitsBounded() {
        ride(30_000, 90f)
        assertTrue(control.localErg(50)); ride(10_000, 90f)
        ride(35_000, 45f)
        ride(1_000, 90f)
        println("rise: power at 90 rpm = ${watts(90f)} level=$resistance")
        assertTrue(control.localErg(300))
        ride(16_000, 90f)
        assertTrue(lines.any { "wait spin-up target=300" in it })
        val jump = lines.last { "to=300" in it }
        println("forgotten-rise: $jump"); println(lines.filter{ "wait spin" in it }.joinToString("\n"))
        assertTrue(jump, "jump" in jump)
    }
}
