package com.spop.poverlay.control

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.exp
import kotlin.math.pow

/** The ERG trace only reports: a traced ride makes exactly the same writes as an untraced one. */
class ErgTraceTest {
    private class Ride(traced: Boolean) {
        var now = 1000L
        var resistance = 30
        val writes = mutableListOf<Pair<Long, Int>>()
        val lines = mutableListOf<String>()
        val control = BikeControl(true, { now }) { writes.add(now to it); resistance = it; true }.apply {
            if (traced) trace = { lines.add(it) }
        }
        // Bike+-like plant, so feed-forward, ramps, arrivals and trims all happen.
        private fun watts(cadence: Float) =
            (exp(1.491016 + 0.108267 * resistance - 0.000584 * resistance * resistance) * (cadence / 90.0).pow(1.7)).toFloat()
        private fun command(vararg bytes: Int) = control.procedure("ble:tr", bytes.map { it.toByte() }.toByteArray())
        private fun ride(seconds: Int, cadence: Float) = repeat(seconds * 10) {
            now += 100
            if (it % 2 == 0) control.acceptSample(BikeSample(watts(cadence), cadence, resistance, now))
            control.tick()
        }
        private fun target(watts: Int) = command(5, watts and 255, watts shr 8)

        fun run(): Ride {
            ride(2, 90f)
            assertEquals(1, command(0).result)
            target(165); ride(60, 90f)
            target(362); ride(30, 97f)
            target(165); ride(40, 91f)
            target(296); ride(20, 93f)
            command(8, 1)
            ride(2, 90f)
            return this
        }
    }

    @Test fun tracingDoesNotChangeControl() {
        val traced = Ride(traced = true).run()
        val plain = Ride(traced = false).run()
        assertTrue(traced.writes.size > 10)
        assertEquals(plain.writes, traced.writes)
        assertEquals(plain.control.state.value, traced.control.state.value)
        assertTrue(plain.lines.isEmpty())
    }

    @Test fun traceCoversCommandsDecisionsAndWrites() {
        val lines = Ride(traced = true).run().lines
        fun has(text: String) = assertTrue("missing \"$text\" in trace:\n" + lines.joinToString("\n"), lines.any { text in it })
        has("cmd client=ble:tr bytes=05 6A 01 op=set-power watts=362 result=1")
        has("op=request-control")
        has("op=stop-pause param=1")
        has("mode Manual->Erg")
        has("target from=165 to=362")
        has("action=jump to=")
        has("why=ramp goal=")
        has("ramp done level=")
        has("arrive level=")
        has("snap mode=Erg target=362")
        has("cadSrc=avg5s")
        // The drop back to 165 W uses the cadence held at 165 W earlier in the ride.
        has("target from=362 to=165")
        assertTrue(lines.single { "target from=362 to=165" in it }.contains("cadSrc=held"))
        has("stop reason=\"Stopped\"")
    }
}
