package com.spop.poverlay.sensor.power

import org.junit.Assert.*
import org.junit.Test

class CyclingPowerMeasurementTest {
    @Test fun decodesSignedLittleEndianWatts() {
        for (watts in listOf(-32768, -100, -1, 0, 250, 1000, 32767)) {
            assertEquals(watts, decodeCyclingPower(byteArrayOf(0, 0, watts.toByte(), (watts shr 8).toByte())))
        }
    }
    @Test fun rejectsTruncatedHeader() {
        for (size in 0..3) assertNull(decodeCyclingPower(ByteArray(size)))
    }
    @Test fun validatesEachOptionalFieldLength() {
        for ((bit, size) in mapOf(0 to 1, 2 to 2, 4 to 6, 5 to 4, 6 to 4, 7 to 4, 8 to 3, 9 to 2, 10 to 2, 11 to 2)) {
            val bytes = ByteArray(4 + size)
            bytes[0] = (1 shl bit).toByte(); bytes[1] = ((1 shl bit) shr 8).toByte()
            bytes[2] = 123
            assertEquals("flag $bit", 123, decodeCyclingPower(bytes))
            assertNull("truncated flag $bit", decodeCyclingPower(bytes.copyOf(bytes.size - 1)))
        }
    }
    @Test fun multipleFieldsAreCumulativeAndContextFlagsAddNoBytes() {
        val bytes = ByteArray(5) // Pedal balance plus its context, torque source, offset indicator.
        bytes[0] = 0x0b; bytes[1] = 0x10; bytes[2] = 99
        assertEquals(99, decodeCyclingPower(bytes))
        bytes[0] = 0x1f // Add torque and wheel revolution data.
        assertNull(decodeCyclingPower(bytes))
        assertEquals(99, decodeCyclingPower(bytes.copyOf(13)))
    }
    @Test fun ignoresReservedFlagsAndUnknownTrailingData() {
        for (flag in listOf(0x20, 0x40, 0x80, 0xe0)) {
            assertEquals(42, decodeCyclingPower(byteArrayOf(0, flag.toByte(), 42, 0)))
            assertEquals(42, decodeCyclingPower(byteArrayOf(0, flag.toByte(), 42, 0, 99, 99)))
            // Known optional fields must still be complete even with RFU flags present.
            assertNull(decodeCyclingPower(byteArrayOf(1, flag.toByte(), 42, 0)))
        }
    }
    @Test fun decodesCapturedP715PacketWithRfuFlags() {
        // Captured on the Peloton: flags 0x602f, power 32 W, optional and extension data.
        val packet = "2f6020005ed7a261b1e1653a4a1412".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(32, decodeCyclingPower(packet))
        assertEquals(32, decodeCyclingPower(packet.copyOf(11)))
        assertNull(decodeCyclingPower(packet.copyOf(10)))
    }
    @Test fun freshnessIncludesZeroAndRejectsMissingOldOrFutureData() {
        assertEquals(0f, freshExternalPower(ExternalPowerReading(0, 1000, "a"), 1000))
        assertEquals(0f, freshExternalPower(ExternalPowerReading(-100, 1000, "a"), 1000))
        assertEquals(200f, freshExternalPower(ExternalPowerReading(200, 1000, "a"), 4000))
        assertNull(freshExternalPower(ExternalPowerReading(200, 1000, "a"), 4001))
        assertNull(freshExternalPower(ExternalPowerReading(200, 1000, "a"), 999))
        assertNull(freshExternalPower(null, 1000))
    }
}
