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
    fun `advertised control range and telemetry describe the same physical levels`() {
        val range = FitnessMachineData.supportedResistanceRange()
        assertArrayEquals(byteArrayOf(0, 0, 0xe8.toByte(), 3, 10, 0), range)
        val maximum = (range[2].toInt() and 0xFF) or ((range[3].toInt() and 0xFF) shl 8)
        assertEquals(maximum / 10, decodedResistance(100f))
        assertEquals(maximum / 10, decodedResistance(100.01f))
    }

    @Test
    fun `every signed control operand agrees with the advertised range and increment`() {
        val control = com.spop.poverlay.control.BikeControl(true, { 1000L }) { error("Targets must not write immediately") }
        control.acceptSample(com.spop.poverlay.control.BikeSample(250f, 90f, 40, 1000))
        assertEquals(1, control.procedure("ble:test", byteArrayOf(0)).result)
        val range = FitnessMachineData.supportedResistanceRange()
        fun uint16(offset: Int) = (range[offset].toInt() and 255) or ((range[offset + 1].toInt() and 255) shl 8)
        val min = uint16(0)
        val max = uint16(2)
        val increment = uint16(4)
        for (raw in Short.MIN_VALUE..Short.MAX_VALUE) {
            val result = control.procedure("ble:test", byteArrayOf(4, raw.toByte(), (raw shr 8).toByte()))
            val accepted = raw in min..max && (raw - min) % increment == 0
            assertEquals("raw=$raw", if (accepted) 1 else 3, result.result)
            if (accepted) {
                val level = control.state.value.targetResistance
                assertEquals(raw, level * 10)
                assertEquals(level, decodedResistance(level.toFloat()))
                assertArrayEquals(byteArrayOf(7, raw.toByte(), (raw shr 8).toByte()), result.status)
            }
        }
    }

    @Test
    fun `SmartSpin2k two byte whole level decoder retains resistance cadence and power`() {
        // Independent legacy receiver offsets from SmartSpin2k's
        // FitnessMachineIndoorBikeData.cpp: width=2, signed=true, resolution=1.
        for (level in 0..100) for (hr in listOf(null, 140)) {
            val packet = FitnessMachineData.encode(90f, 250f, 20f, level.toFloat(), hr)
            fun uint16(offset: Int) = (packet[offset].toInt() and 255) or ((packet[offset + 1].toInt() and 255) shl 8)
            assertEquals(if (hr == null) 10 else 11, packet.size)
            assertEquals(if (hr == null) 0x64 else 0x264, uint16(0))
            assertEquals(180, uint16(4))
            assertEquals(level, uint16(6).toShort().toInt())
            assertEquals(250, uint16(8).toShort().toInt())
            if (hr != null) assertEquals(hr, packet[10].toInt() and 255)
        }
    }

    @Test
    fun `SmartSpin2k normalizes the advertised range to its homing scale`() {
        val range = FitnessMachineData.supportedResistanceRange()
        fun uint16(offset: Int) = (range[offset].toInt() and 255) or ((range[offset + 1].toInt() and 255) shl 8)
        // BLE_Client.cpp divides min/max tenths by the increment in whole units.
        val increment = uint16(4) / 10f
        val min = (uint16(0).toShort() / 10f) / increment
        val max = (uint16(2).toShort() / 10f) / increment
        assertEquals(1f, increment, 0f)
        assertEquals(0f, min, 0f)
        assertEquals(100f, max, 0f)
        // Its transition homing requires at least the interior 1..99 levels.
        org.junit.Assert.assertTrue(min <= 1 && max >= 99)
    }

    @Test
    fun `maximum resistance packet preserves flags field order and power`() {
        assertArrayEquals(
            byteArrayOf(0x64, 0x00, 0x92.toByte(), 0x0C, 0xB4.toByte(), 0x00,
                0x64, 0x00, 0xFA.toByte(), 0x00),
            FitnessMachineData.encode(90f, 250f, 20f, 100f)
        )
    }

    @Test
    fun `relayed heart rate is appended after power with its flag`() {
        assertArrayEquals(
            byteArrayOf(0x64, 0x02, 0x92.toByte(), 0x0C, 0xB4.toByte(), 0x00,
                0x31, 0x00, 0xFA.toByte(), 0x00, 0x8C.toByte()),
            FitnessMachineData.encode(90f, 250f, 20f, 49f, 140)
        )
    }

    @Test
    fun `missing or invalid heart rate leaves the packet unchanged`() {
        val plain = FitnessMachineData.encode(90f, 250f, 20f, 49f)
        for (hr in listOf(null, 0, -5, 256)) {
            assertArrayEquals("hr=$hr", plain, FitnessMachineData.encode(90f, 250f, 20f, 49f, hr))
        }
    }
}
