package com.spop.poverlay.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CadenceHistoryTest {
    @Test fun `cadence held near a target is remembered`() {
        val h = CadenceHistory()
        for (s in 0 until 30) h.recordSteady(s * 1000L, 90f, 170)
        for (s in 30 until 60) h.recordSteady(s * 1000L, 97f, 360)
        assertEquals(90f, h.cadenceAt(175, 60_000L)!!, 0f)
        assertEquals(97f, h.cadenceAt(350, 60_000L)!!, 0f)
        assertNull(h.cadenceAt(250, 60_000L))
    }

    @Test fun `the most recent riding at a target wins over older riding`() {
        // Nov 8: warmed up at 88 rpm at 165 W, then every recovery at 72 rpm. The ten-minute
        // median stayed at 87 rpm and sized each recovery five levels too light.
        val h = CadenceHistory()
        for (s in 0 until 80) h.recordSteady(s * 1000L, 88f, 165)
        for (s in 200 until 225) h.recordSteady(s * 1000L, 72f, 165)
        assertEquals(72f, h.cadenceAt(165, 300_000L)!!, 0f)
    }

    @Test fun `a new cadence takes over once it fills most of the last 20 samples`() {
        // The median of the last 20 is a step: after 88 rpm, 9 samples at 72 still read 88,
        // 10 split it (80), 11 read 72. About 11 s of settled riding at the new cadence.
        for ((recent, expected) in listOf(9 to 88f, 10 to 80f, 11 to 72f)) {
            val h = CadenceHistory()
            for (s in 0 until 60) h.recordSteady(s * 1000L, 88f, 165)
            for (s in 100 until 100 + recent) h.recordSteady(s * 1000L, 72f, 165)
            assertEquals("after $recent samples at 72", expected, h.cadenceAt(165, 120_000L)!!, 0f)
        }
    }

    @Test fun `faster spinning in efforts does not leak into the recovery cadence`() {
        val h = CadenceHistory()
        for (s in 0 until 30) h.recordSteady(s * 1000L, 90f, 170)
        for (s in 30 until 60) h.recordSteady(s * 1000L, 97f, 360)
        for (s in 60 until 90) h.recordSteady(s * 1000L, 98f, 360)
        assertEquals(90f, h.cadenceAt(165, 90_000L)!!, 0f)
    }

    @Test fun `too little steady riding gives no memory`() {
        val h = CadenceHistory()
        for (s in 0 until 10) h.recordSteady(s * 1000L, 90f, 170)
        assertNull(h.cadenceAt(170, 10_000L))
    }

    @Test fun `samples are kept once per second and forgotten after ten minutes`() {
        val h = CadenceHistory()
        for (ms in 0 until 30_000 step 200) h.recordSteady(ms.toLong(), 90f, 170)
        assertEquals(90f, h.cadenceAt(170, 30_000L)!!, 0f)
        assertNull(h.cadenceAt(170, 30_000L + 600_001L))
    }

    @Test fun `typical cadence is the two-minute median`() {
        val h = CadenceHistory()
        for (s in 0 until 60) h.record(s * 1000L, if (s % 3 == 0) 100f else 88f)
        assertEquals(88f, h.typicalCadence(60_000L)!!, 0f)
        assertNull(CadenceHistory().apply { record(0L, 90f) }.typicalCadence(0L))
    }

    @Test fun `coasting is ignored`() {
        val h = CadenceHistory()
        for (s in 0 until 60) h.record(s * 1000L, 20f)
        assertNull(h.typicalCadence(60_000L))
    }
}
