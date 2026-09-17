package com.spop.poverlay.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FitnessMachineDataTest {
    private fun decodedResistance(value: Float): Int {
        val packet = FitnessMachineData.encode(90f, 250f, 20f, value)
        return (packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)
    }

    @Test
    fun `every whole resistance level round trips exactly`() {
        for (level in 0..100) {
            assertEquals("Resistance $level", level, decodedResistance(level.toFloat()))
        }
    }

    @Test
    fun `float error below a whole level does not lose one level`() {
        for (level in 1..100) {
            val justBelow = Float.fromBits(level.toFloat().toBits() - 1)
            assertEquals("Resistance $justBelow", level, decodedResistance(justBelow))
        }
    }

    @Test
    fun `fractional levels round to nearest with ties rounding up`() {
        assertEquals(99, decodedResistance(99.49f))
        assertEquals(100, decodedResistance(99.5f))
        assertEquals(100, decodedResistance(99.7f))
        assertEquals(50, decodedResistance(49.5f))
    }

    @Test
    fun `invalid and out of range readings cannot wrap on the wire`() {
        assertEquals(0, decodedResistance(Float.NaN))
        assertEquals(0, decodedResistance(Float.POSITIVE_INFINITY))
        assertEquals(0, decodedResistance(Float.NEGATIVE_INFINITY))
        assertEquals(0, decodedResistance(-1f))
        assertEquals(100, decodedResistance(101f))
        assertEquals(100, decodedResistance(Float.MAX_VALUE))
    }

    @Test
    fun `advertised maximum matches the maximum transmitted resistance`() {
        val range = FitnessMachineData.supportedResistanceRange()
        assertArrayEquals(byteArrayOf(1, 0, 100, 0, 1, 0), range)
        val maximum = (range[2].toInt() and 0xFF) or ((range[3].toInt() and 0xFF) shl 8)
        assertEquals(maximum, decodedResistance(100f))
        assertEquals(maximum, decodedResistance(100.01f))
    }

    @Test
    fun `maximum resistance packet preserves flags field order and power`() {
        assertArrayEquals(
            byteArrayOf(0x64, 0x00, 0x92.toByte(), 0x0C, 0xB4.toByte(), 0x00,
                0x64, 0x00, 0xFA.toByte(), 0x00),
            FitnessMachineData.encode(90f, 250f, 20f, 100f)
        )
    }
}
