package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/**
 * Regression tests for the Oct 31 independent review: spin-up at low cadence, drops that must
 * never add resistance, cancelled ramps, and knob moves during a ramp. The bike is a plant whose
 * power follows the Bike+ seed model at the rider's cadence.
 */
class ErgSafetyTest {
    private var now = 1000L
    private var resistance = 40
    private val writes = mutableListOf<Int>()
    private val lines = mutableListOf<String>()
    private val control = BikeControl(true, { now }) { writes.add(it); resistance = it; true }.apply {
        trace = { lines.add("[$now] $it") }
    }
    private fun watts(cadence: Float) =
        (exp(1.491016 + 0.108267 * resistance - 0.000584 * resistance * resistance) * (cadence / 90.0).pow(1.7)).toFloat()
    /** Ride [ms] at [cadence]: 100 ms ticks, a bike sample every 200 ms. */
    private fun ride(ms: Int, cadence: Float, power: Float? = null) {
        var t = 0
        while (t < ms) {
            now += 100; t += 100
            if (t % 200 == 0) control.acceptSample(BikeSample(power ?: watts(cadence), cadence, resistance, now))
            control.tick()
        }
    }
    private fun cadenceSteps(seconds: Int, pattern: (Int) -> Float) = repeat(seconds * 5) { ride(200, pattern(it)) }

    @Test fun lowCadenceRiderGetsFeedbackNotAJump() {
        ride(30_000, 45f)                       // a 45 rpm drill: pedaling (>= 40) but below the model's 50 rpm
        assertTrue(control.localErg(300))
        ride(30_000, 45f)
        assertTrue("feedback must raise resistance, got $writes", resistance > 60)
        assertTrue(lines.any { "trim(low cadence)" in it })
        assertFalse(lines.any { "wait spin-up" in it })
        // Feedback at 3 levels/s, not a modeled ramp: no write moves more than 1 level per tick.
        assertTrue(writes.zipWithNext().all { (a, b) -> b - a in -1..1 })
    }

    @Test fun riderBelowFortyGetsFeedbackOnceTheWaitRunsOut() {
        ride(30_000, 30f)                       // turning over at 30 rpm: never counts as pedaling
        assertTrue(control.localErg(200))
        ride(10_000, 30f)
        assertTrue("held while the wait runs, got $writes", writes.isEmpty())
        ride(20_000, 30f)
        assertTrue("feedback after the bounded wait, got $writes", writes.isNotEmpty())
    }

    @Test fun cadenceHoveringAroundFiftyStillGetsResistance() {
        var i = 0
        cadenceSteps(60) { if (i++ % 10 == 0) 48f else 52f }
        assertTrue(control.localErg(300))
        cadenceSteps(60) { if (i++ % 10 == 0) 48f else 52f }
        assertTrue("expected a jump or trim, writes=$writes", writes.isNotEmpty())
    }

    @Test fun briefDipToFortyDoesNotRestartTheWait() {
        ride(30_000, 85f)                       // spin-up long over
        ride(2_000, 40f)                        // dip
        ride(400, 85f)
        assertTrue(control.localErg(220))
        ride(1_000, 85f)
        assertTrue("a dip above 25 rpm is still pedaling: jump at once, got $writes", writes.isNotEmpty())
        assertFalse(lines.any { "wait spin-up" in it })
    }

    @Test fun stopBelowTwentyFiveRestartsTheWait() {
        ride(30_000, 85f)
        ride(2_000, 0f)
        ride(1_000, 70f)
        assertTrue(control.localErg(220))
        ride(2_000, 80f)
        assertTrue(lines.any { "wait spin-up target=220" in it })
        assertTrue(writes.isEmpty())
    }

    @Test fun dropFromLowCadenceMemoryNeverAddsResistance() {
        ride(20_000, 60f)
        assertTrue(control.localErg(150)); ride(40_000, 60f)      // 150 W held at 60 rpm (grinding warm-up)
        assertTrue(control.localErg(300)); ride(30_000, 95f)      // hard interval at 95 rpm
        val before = resistance
        val writesBefore = writes.size
        assertTrue(control.localErg(150)); ride(3_000, 95f)       // recovery
        assertTrue("drop to 150 W raised resistance $before -> $resistance", resistance <= before)
        assertTrue(writes.drop(writesBefore).all { it <= before })
        assertTrue(lines.any { "to=150" in it && "cadSrc=floor" in it })
    }

    @Test fun dropAfterHighCadenceEffortStillUsesHeldCadence() {
        // The original fix: 165 W held at 89 rpm, 362 W spun at 97, recovery settles back to 89.
        ride(20_000, 89f)
        assertTrue(control.localErg(165)); ride(40_000, 89f)
        assertTrue(control.localErg(362)); ride(30_000, 97f)
        assertTrue(control.localErg(165)); ride(1_000, 97f)
        val jump = lines.last { "target from=362 to=165" in it }
        assertTrue(jump, "cadSrc=held" in jump)
        val expected = control.powerModel.nearestLevelFor(165f, 89f)!!
        ride(4_000, 89f)
        assertEquals(expected.toDouble(), resistance.toDouble(), 1.0)
    }

    @Test fun cancelledRampDoesNotResumeAsAFastJump() {
        resistance = 44
        ride(20_000, 90f)
        assertTrue(control.localErg(320))
        ride(400, 90f)
        ride(2_000, 0f)                         // stop mid-ramp
        val afterStop = resistance
        ride(2_000, 30f)                        // turning the pedals slowly again
        assertEquals("nothing added while restarting at 30 rpm", afterStop, resistance)
        ride(2_000, 80f)
        assertEquals("still spinning up at 80 rpm", afterStop, resistance)
    }

    @Test fun noWriteExceedsTheRampSpeed() {
        resistance = 30
        control.tune(2, .007f, rampRate = 5)
        ride(20_000, 90f)
        assertTrue(control.localErg(320))
        ride(10_000, 90f)
        // 5 levels/s with 100 ms ticks: never more than one level per write.
        assertTrue(writes.zipWithNext().all { (a, b) -> b - a in -1..1 })
    }

    @Test fun smallKnobMoveAgainstTheRampStopsControl() {
        resistance = 44
        ride(20_000, 90f)
        assertTrue(control.localErg(320))
        ride(400, 90f)
        // The rider turns the knob down 2 levels and the bike reports it before the next write.
        // (A move the ramp's next write overrides before any report cannot be seen.)
        resistance -= 2
        now += 100
        control.acceptSample(BikeSample(watts(90f), 90f, resistance, now)); control.tick()
        assertEquals(ControlMode.Manual, control.state.value.mode)
        assertTrue(lines.any { "ramp knob override" in it })
        val atStop = writes.size
        ride(2_000, 90f)
        assertEquals("no writes after the override", atStop, writes.size)
    }

    @Test fun echoLagDuringARampIsNotAKnobMove() {
        resistance = 44
        var held = 44
        val lagging = BikeControl(true, { now }) { writes.add(it); true }   // the bike reports late
        var t = 0
        repeat(150) { lagging.acceptSample(BikeSample(170f, 90f, 44, now)); now += 100; lagging.tick() }
        assertTrue(lagging.localErg(320))
        repeat(40) {
            now += 100; t += 100
            if (t % 200 == 0) { held = minOf(writes.lastOrNull() ?: held, held + 2); lagging.acceptSample(BikeSample(170f, 90f, held, now)) }
            lagging.tick()
        }
        assertEquals(ControlMode.Erg, lagging.state.value.mode)
    }

    @Test fun spinUpProtectionReturnsAfterReconnect() {
        ride(30_000, 85f)
        now += 3_000; control.tick()            // telemetry gap: data goes stale
        ride(400, 85f)
        assertTrue(control.localErg(250))
        ride(1_000, 85f)
        assertTrue(lines.any { "wait spin-up target=250" in it })
    }
}
