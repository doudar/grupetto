package com.spop.poverlay.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure FTMS Treadmill Data (0x2ACD) packer. Mirrors the repo's
 * style of testing byte-packing logic without touching Android GATT objects.
 *
 * Layout (little-endian): flags(u16) | instantaneous speed(u16, 0.01 km/h) |
 * inclination(s16, 0.1 %) | ramp angle(s16, 0.1 deg).
 */
class TreadmillDataPacketTest {

    // Same mph -> km/h factor the service applies before packing.
    private fun kmh(mph: Float) = mph * 1.60934f

    @Test
    fun `flags set inclination-and-ramp bit only`() {
        val packet = FitnessMachineConstants.buildTreadmillDataPacket(kmh(3.2f), 8.0f)
        // bit 3 => 0x08, high byte 0x00. Bit 0 (More Data) is 0 => speed present.
        assertEquals(0x08.toByte(), packet[0])
        assertEquals(0x00.toByte(), packet[1])
    }

    @Test
    fun `3_2 mph and 8_0 percent packs to expected bytes`() {
        // 3.2 mph -> 5.1499 km/h -> 515 (0x0203); 8.0% -> 80 (0x0050);
        // ramp = atan(8/100) = 4.574 deg -> 46 (0x002E).
        val packet = FitnessMachineConstants.buildTreadmillDataPacket(kmh(3.2f), 8.0f)
        assertArrayEquals(
            byteArrayOf(0x08, 0x00, 0x03, 0x02, 0x50, 0x00, 0x2E, 0x00),
            packet
        )
    }

    @Test
    fun `speed encodes as 0_01 km per hour uint16 little-endian`() {
        // 6.7 mph -> 10.7826 km/h -> 1078 (0x0436)
        val packet = FitnessMachineConstants.buildTreadmillDataPacket(kmh(6.7f), 3.5f)
        assertEquals(0x36.toByte(), packet[2])
        assertEquals(0x04.toByte(), packet[3])
        // 3.5% -> 35 (0x0023)
        assertEquals(0x23.toByte(), packet[4])
        assertEquals(0x00.toByte(), packet[5])
    }

    @Test
    fun `zero speed and zero incline packs to zeros with flags`() {
        val packet = FitnessMachineConstants.buildTreadmillDataPacket(0f, 0f)
        assertArrayEquals(
            byteArrayOf(0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            packet
        )
    }

    @Test
    fun `absurdly large speed clamps to uint16 max instead of wrapping`() {
        // 10000 km/h -> 1_000_000 (0x0F4240) which would wrap to 0x4240 if unbounded.
        // Clamped to 65535 (0xFFFF).
        val packet = FitnessMachineConstants.buildTreadmillDataPacket(10000f, 0f)
        assertEquals(0xFF.toByte(), packet[2])
        assertEquals(0xFF.toByte(), packet[3])
    }

    @Test
    fun `large negative incline clamps to sint16 min for incline and ramp`() {
        // -5000% -> -50000 (below -32768) clamps to -32768 (0x8000 little-endian);
        // ramp = atan(-50) -> -88.85 deg -> -888 clamps within range but stays negative.
        val packet = FitnessMachineConstants.buildTreadmillDataPacket(kmh(1f), -5000f)
        // Inclination clamped to -32768 = 0x8000.
        assertEquals(0x00.toByte(), packet[4])
        assertEquals(0x80.toByte(), packet[5])
        // Ramp = round(atan(-5000/100) deg * 10) = -889 (0xFC87) -> within sint16, negative.
        assertEquals(0x87.toByte(), packet[6])
        assertEquals(0xFC.toByte(), packet[7])
    }
}
