package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

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
        // The rider turns the knob down 2 levels. The ramp had written one level past the last
        // reading, so the bike reports only one level back: the ramp pauses (no write that could
        // hide the turn) and the next reading confirms it.
        // (A turn the next write overrides before any report cannot be seen, on the bike either.)
        resistance -= 2
        val atTurn = writes.size
        now += 100
        control.acceptSample(BikeSample(watts(90f), 90f, resistance, now)); control.tick()
        ride(400, 90f)
        assertEquals("no ramp write between the turn and the stop", atTurn, writes.size)
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

    /** A bike that reports late: the echo follows the last write by up to [lag] levels per sample. */
    private fun laggingBike(): BikeControl = BikeControl(true, { now }) { writes.add(it); true }.apply {
        trace = { lines.add("[$now] $it") }
    }
    private fun settle(bike: BikeControl, level: Int) =
        repeat(150) { bike.acceptSample(BikeSample(170f, 90f, level, now)); now += 100; bike.tick() }

    @Test fun singleOneLevelBlipDuringRampIsNotAKnobMove() {
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0
        repeat(40) {
            now += 100; t += 100
            if (t % 200 == 0) {
                // the echo trails the writes by ~2 levels and once reports the previous level again
                val target = writes.lastOrNull() ?: reported
                reported = if (t == 1200) reported - 1 else minOf(target, reported + 2)
                bike.acceptSample(BikeSample(170f, 90f, reported, now))
            }
            bike.tick()
        }
        assertEquals(lines.joinToString("\n"), ControlMode.Erg, bike.state.value.mode)
    }

    @Test fun oneLevelBackOnTwoSamplesIsAKnobMove() {
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0; var turned = false
        repeat(30) {
            now += 100; t += 100
            if (t % 200 == 0) {
                val target = writes.lastOrNull() ?: reported
                // at 1.0 s the rider turns the knob one level down and holds it there
                if (t == 1000) { reported -= 1; turned = true } else if (!turned) reported = minOf(target, reported + 2)
                bike.acceptSample(BikeSample(170f, 90f, reported, now))
            }
            bike.tick()
        }
        assertEquals(ControlMode.Manual, bike.state.value.mode)
        assertTrue(lines.any { "ramp knob override" in it })
    }

    @Test fun twoLevelKnobTurnStopsAtOnce() {
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0
        repeat(10) {
            now += 100; t += 100
            if (t % 200 == 0) { reported = minOf(writes.lastOrNull() ?: reported, reported + 2); bike.acceptSample(BikeSample(170f, 90f, reported, now)) }
            bike.tick()
        }
        val writesBefore = writes.size
        reported -= 2
        now += 100; bike.acceptSample(BikeSample(170f, 90f, reported, now)); bike.tick()
        assertEquals(ControlMode.Manual, bike.state.value.mode)
        assertEquals(writesBefore, writes.size)
    }

    @Test fun targetChangeMidRampKeepsControl() {
        // The echo trails the ramp; TrainerRoad lowers the target slightly mid-ramp. The last write is
        // ahead of the echo, so the arriving echo must not be mistaken for a knob override.
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0
        repeat(80) {
            now += 100; t += 100
            if (t == 600) assertTrue(bike.localErg(300))
            if (t % 200 == 0) {
                val target = writes.lastOrNull() ?: reported
                reported = if (target > reported) minOf(target, reported + 2) else maxOf(target, reported - 2)
                bike.acceptSample(BikeSample(170f, 90f, reported, now))
            }
            bike.tick()
        }
        assertEquals(lines.joinToString("\n"), ControlMode.Erg, bike.state.value.mode)
        assertTrue(lines.any { "target from=320 to=300" in it && "jump to=" in it })
    }

    @Test fun smallDownwardAdjustmentDuringARiseStillJumps() {
        ride(30_000, 90f)
        assertTrue(control.localErg(150)); ride(20_000, 90f)
        val base = resistance
        assertTrue(control.localErg(320)); ride(400, 90f)
        assertTrue(control.localErg(300)); ride(12_000, 90f)
        val expected = control.powerModel.nearestLevelFor(300f, 90f)!!
        assertTrue("expected about level $expected from $base, got $resistance\n" + lines.joinToString("\n"),
            resistance in expected - 2..expected + 2)
        assertFalse(lines.any { "target from=320 to=300" in it && "jump to=$base " in it })
    }

    @Test fun aStopDuringTheWaitRestartsTheWait() {
        ride(30_000, 85f)
        ride(2_000, 0f)
        ride(1_000, 70f)
        assertTrue(control.localErg(220))
        ride(2_000, 80f)
        assertTrue(lines.any { "wait spin-up target=220" in it })
        ride(20_000, 0f)                       // stopped long enough for the old window to lapse
        val n = lines.size
        ride(3_000, 80f)
        assertTrue("a second wait expected", lines.drop(n).any { "wait spin-up" in it })
    }

    @Test fun longLowCadenceStretchForgetsTheOldAverage() {
        ride(30_000, 90f)
        assertTrue(control.localErg(150)); ride(20_000, 90f)
        ride(60_000, 35f)                      // a minute at 35 rpm (still pedaling)
        assertTrue(control.localErg(300))
        ride(2_000, 35f)
        val target = lines.last { "target from=150 to=300" in it }
        assertTrue(target, "trim(low cadence)" in target)
    }

    @Test fun aJumpToTheCurrentLevelIsATrim() {
        resistance = 44
        ride(20_000, 90f)                      // steady at level 44, 90 rpm
        ride(200, 80f)                         // cadence dips: power reads ~25 W low, the 5 s average barely moves
        val avg = 90f + .2f / 5.2f * (80f - 90f)
        val target = control.powerModel.wattsAt(44, avg).roundToInt()
        assertEquals(44, control.powerModel.nearestLevelFor(target.toFloat(), avg))
        assertTrue(control.localErg(target))   // the model says the current level already gives this
        ride(200, 80f)
        val line = lines.last { "target from=null to=$target" in it }
        assertTrue(line, "trim(at level)" in line)
        assertFalse(lines.any { "arrive" in it || "why=ramp" in it })
    }

    // ---- Third review round (Fable, ccb81d9) ----

    /** After a long low-cadence stretch the average is forgotten; the rise back must wait, not jump for 55 rpm. */
    @Test fun spinUpAfterForgottenLowCadenceStretchWaits() {
        ride(30_000, 90f)                          // warm-up at 90 rpm (manual)
        ride(60_000, 35f)                          // a minute of slow grinding, still pedaling
        ride(1_000, 55f)                           // spinning up as the interval begins
        assertTrue(control.localErg(300))
        ride(1_000, 60f); ride(1_000, 70f); ride(1_000, 80f)
        var peak = 0f
        repeat(100) { ride(200, 90f); peak = maxOf(peak, watts(90f)) }
        assertTrue(lines.joinToString("\n"), lines.any { "wait spin-up target=300" in it })
        val ideal = control.powerModel.nearestLevelFor(300f, 90f)!!
        val jump = lines.last { "target from=null to=300" in it }
        val level = Regex("jump to=(\\d+)").find(jump)?.groupValues?.get(1)?.toInt()
        assertTrue(jump, level == null || level in ideal - 3..ideal + 3)
        assertTrue("peak $peak W at 90 rpm for a 300 W target", peak < 340f)
    }

    /** A one-notch knob turn the bike reports once, after which the motor would follow later writes. */
    @Test fun oneNotchKnobTurnDuringRampStopsControl() {
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0; var writesAtTurn = -1
        repeat(40) {
            now += 100; t += 100
            if (t % 200 == 0) {
                // The echo trails the writes by ~1 level. At 1.0 s the rider turns the knob one notch down;
                // from then on the bike only moves if a new write arrives after the turn.
                if (t == 1000) { reported -= 1; writesAtTurn = writes.size }
                else if (writesAtTurn < 0) reported = minOf(writes.lastOrNull() ?: reported, reported + 1)
                else if (writes.size > writesAtTurn) reported = writes.last()
                bike.acceptSample(BikeSample(170f, 90f, reported, now))
            }
            bike.tick()
        }
        assertEquals(lines.joinToString("\n"), ControlMode.Manual, bike.state.value.mode)
        assertTrue(lines.any { "knob override" in it })
    }

    /** Stopping mid-ramp when the bike never completes the last write must not switch ERG off. */
    @Test fun stopPedalingMidRampWithoutTheLastEchoKeepsErg() {
        val bike = laggingBike(); settle(bike, 44)
        assertTrue(bike.localErg(320))
        var reported = 44; var t = 0
        repeat(60) {
            now += 100; t += 100
            if (t % 200 == 0) {
                val target = writes.lastOrNull() ?: reported
                val cad = if (t >= 1000) 0f else 90f
                if (t < 1000) reported = minOf(target, reported + 2)   // after the stop the bike holds where it is
                bike.acceptSample(BikeSample(if (cad == 0f) 0f else 170f, cad, reported, now))
            }
            bike.tick()
        }
        assertEquals(bike.state.value.message, ControlMode.Erg, bike.state.value.mode)
    }

    /** A brief power wobble below a new lower target is still a drop: it never adds resistance. */
    @Test fun lowerTargetDuringPowerWobbleNeverAddsResistance() {
        ride(30_000, 90f)
        assertTrue(control.localErg(300)); ride(30_000, 90f)
        val before = resistance
        ride(200, 90f, power = 200f)
        assertTrue(control.localErg(220))
        ride(200, 90f, power = 200f)
        ride(6_000, 90f)
        assertTrue("before=$before after=$resistance", resistance <= before)
    }
}
