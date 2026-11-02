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
