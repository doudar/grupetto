package com.spop.poverlay.sensor.v1legacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensorDataProtocolTest {
    @Test
    fun `captured stationary bike responses decode to zero RPM zero watts and 31 resistance`() {
        assertEquals(0f, SensorDataRead.Cadence.decodeResponse("F1 41 03 30 30 30 C5 F6"))
        assertEquals(0f, SensorDataRead.Power.decodeResponse("F1 44 05 30 30 30 30 30 2A F6"))
        assertEquals(31f, SensorDataRead.Resistance.decodeResponse("F1 49 03 31 33 30 D1 F6"))
    }

    @Test
    fun `power uses tenths while cadence and resistance use whole units`() {
        // Synthetic nonzero packets following the inspected Peloton V1DataExtractor.
        assertEquals(250.5f, SensorDataRead.Power.decodeResponse("F1 44 05 35 30 35 32 30 36 F6"))
        assertEquals(87f, SensorDataRead.Cadence.decodeResponse("F1 41 03 37 38 30 D4 F6"))
        assertEquals(100f, SensorDataRead.Resistance.decodeResponse("F1 49 03 30 30 31 CE F6"))
    }

    @Test
    fun `service timeout and absent replies are not valid zero readings`() {
        for (read in SensorDataRead.entries) {
            assertNull(read.decodeResponse("TIME_OUT"))
            assertNull(read.decodeResponse(null))
            assertNull(read.decodeResponse(""))
        }
    }

    @Test
    fun `bad checksum and responses to a different command are rejected`() {
        assertNull(SensorDataRead.Resistance.decodeResponse("F1 49 03 31 33 30 D0 F6"))
        assertNull(SensorDataRead.Cadence.decodeResponse("F1 49 03 31 33 30 D1 F6"))
    }

    @Test
    fun `truncated or malformed packets are rejected`() {
        val invalid = listOf(
            "F1 49 03 31 33 30 D1", // Missing terminator.
            "F1 49 02 31 33 30 D0 F6", // Wrong declared length, valid checksum.
            "F1 49 03 31 33 30 D1 F5", // Wrong terminator.
            "F1 49 03 41 33 30 E1 F6", // Non-digit, valid checksum.
            "F1 49 03 GG 33 30 D1 F6",
            "F3 49 03 31 33 30 D3 F6", // Error frame, valid checksum.
            "F1 49 03 31 33 30 D1 F6 00", // Extra data.
        )
        invalid.forEach { assertNull(it, SensorDataRead.Resistance.decodeResponse(it)) }
    }

    @Test
    fun `out of range resistance is not published`() {
        assertNull(SensorDataRead.Resistance.decodeResponse("F1 49 03 31 30 31 CF F6"))
    }
}
