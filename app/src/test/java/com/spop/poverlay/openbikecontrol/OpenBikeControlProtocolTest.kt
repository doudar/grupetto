package com.spop.poverlay.openbikecontrol

import org.junit.Assert.*
import org.junit.Test

class OpenBikeControlProtocolTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun buttons(vararg pairs: Pair<Int, Int>) = ObcMessage.Buttons(pairs.toList())

    @Test fun `app info matches the spec example`() {
        // PROTOCOL example: App "zwift", version "1.52.0", buttons 0x01 0x02 0x10 0x14.
        val expected = bytes(0x04, 0x01, 0x05) + "zwift".toByteArray() + bytes(0x06) + "1.52.0".toByteArray() +
            bytes(0x04, 0x01, 0x02, 0x10, 0x14)
        assertArrayEquals(expected, OpenBikeControl.encodeAppInfo("1.52.0", listOf(0x01, 0x02, 0x10, 0x14), "zwift"))
    }

    @Test fun `grupetto announces shift and difficulty buttons`() {
        val info = OpenBikeControl.encodeAppInfo("1.0")
        assertArrayEquals(bytes(0x04, 0x01, 8) + "grupetto".toByteArray() + bytes(3) + "1.0".toByteArray() +
            bytes(4, 0x01, 0x02, 0x30, 0x31), info)
    }

    @Test fun `app info strings are capped at 32 bytes`() {
        val info = OpenBikeControl.encodeAppInfo("v".repeat(40), emptyList(), "a".repeat(40))
        assertEquals(1 + 1 + 1 + 32 + 1 + 32 + 1, info.size)
        assertEquals(32, info[2].toInt())
        assertEquals(32, info[35].toInt())
    }

    @Test fun `shift and difficulty buttons map to shift directions`() {
        assertEquals(1, OpenBikeControl.shiftDirection(0x01))
        assertEquals(-1, OpenBikeControl.shiftDirection(0x02))
        assertEquals(1, OpenBikeControl.shiftDirection(0x30))
        assertEquals(-1, OpenBikeControl.shiftDirection(0x31))
        assertNull(OpenBikeControl.shiftDirection(0x03))
        assertNull(OpenBikeControl.shiftDirection(0x14))
    }

    @Test fun `single messages decode`() {
        assertEquals(buttons(0x01 to 1), decodeObcMessage(bytes(0x01, 0x01, 0x01)))
        assertEquals(buttons(0x01 to 1, 0x02 to 0), decodeObcMessage(bytes(0x01, 0x01, 0x01, 0x02, 0x00)))
        assertEquals(ObcMessage.DeviceStatus(85, true), decodeObcMessage(bytes(0x02, 0x55, 0x01)))
        assertEquals(ObcMessage.DeviceStatus(null, true), decodeObcMessage(bytes(0x02, 0xFF, 0x01)))
        assertNull(decodeObcMessage(bytes(0x01, 0x01)))
        assertNull(decodeObcMessage(bytes(0x03, 0x01, 0x00, 0x00)))
        assertNull(decodeObcMessage(ByteArray(0)))
    }

    @Test fun `press and release in one read are two messages`() {
        val decoder = ObcStreamDecoder()
        assertEquals(listOf(buttons(0x01 to 1), buttons(0x01 to 0)),
            decoder.offer(bytes(0x01, 0x01, 0x01, 0x01, 0x01, 0x00), 0))
    }

    @Test fun `several presses in one read split into the shortest messages`() {
        val decoder = ObcStreamDecoder()
        assertEquals(listOf(buttons(0x01 to 1), buttons(0x01 to 0), buttons(0x02 to 1), buttons(0x02 to 0)),
            decoder.offer(bytes(0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x01, 0x02, 0x01, 0x01, 0x02, 0x00), 0))
    }

    @Test fun `a message with two buttons stays one message`() {
        assertEquals(listOf(buttons(0x30 to 1, 0x01 to 1)), ObcStreamDecoder().offer(bytes(0x01, 0x30, 0x01, 0x01, 0x01), 0))
    }

    @Test fun `status between button messages`() {
        assertEquals(listOf(buttons(0x01 to 1), ObcMessage.DeviceStatus(50, true), buttons(0x01 to 0)),
            ObcStreamDecoder().offer(bytes(0x01, 0x01, 0x01, 0x02, 50, 0x01, 0x01, 0x01, 0x00), 0))
    }

    @Test fun `a message split across reads is joined`() {
        val decoder = ObcStreamDecoder()
        assertEquals(emptyList<ObcMessage>(), decoder.offer(bytes(0x01, 0x02), 0))
        assertEquals(listOf(buttons(0x02 to 1)), decoder.offer(bytes(0x01), 10))
    }

    @Test fun `a stale fragment is dropped`() {
        val decoder = ObcStreamDecoder(staleMs = 500)
        decoder.offer(bytes(0x01, 0x02), 0)
        assertEquals(listOf(buttons(0x01 to 1)), decoder.offer(bytes(0x01, 0x01, 0x01), 1000))
    }

    @Test fun `unknown data is discarded and the stream recovers`() {
        val decoder = ObcStreamDecoder()
        assertEquals(emptyList<ObcMessage>(), decoder.offer(bytes(0x7F, 0x01, 0x01), 0))
        assertEquals(listOf(buttons(0x01 to 1)), decoder.offer(bytes(0x01, 0x01, 0x01), 1))
    }

    @Test fun `an oversized fragment is discarded`() {
        val decoder = ObcStreamDecoder(maxBytes = 8)
        decoder.offer(ByteArray(10) { 1 }, 0)
        assertEquals(listOf(buttons(0x02 to 1)), decoder.offer(bytes(0x01, 0x02, 0x01), 1))
    }

    @Test fun `an empty button state changes nothing`() {
        assertEquals(listOf(buttons()), ObcStreamDecoder().offer(bytes(0x01), 0))
    }

    @Test fun `each press shifts once`() {
        val shifts = mutableListOf<Int>()
        val tracker = ObcButtonTracker { direction, _ -> shifts += direction }
        tracker.handle(buttons(0x01 to 1))
        tracker.handle(buttons(0x01 to 1)) // repeated pressed state while held
        tracker.handle(buttons(0x01 to 0))
        tracker.handle(buttons(0x01 to 1))
        tracker.handle(buttons(0x02 to 1, 0x01 to 0))
        tracker.handle(buttons(0x31 to 1))
        tracker.handle(ObcMessage.DeviceStatus(80, true))
        tracker.handle(buttons(0x14 to 1)) // select: ignored
        assertEquals(listOf(1, 1, -1, -1), shifts)
    }

    @Test fun `reset forgets held buttons`() {
        val shifts = mutableListOf<Int>()
        val tracker = ObcButtonTracker { direction, _ -> shifts += direction }
        tracker.handle(buttons(0x01 to 1))
        tracker.reset()
        tracker.handle(buttons(0x01 to 1))
        assertEquals(listOf(1, 1), shifts)
    }

    @Test fun `link labels`() {
        assertEquals("Off", obcLinkLabel(ObcLink.Off))
        assertEquals("Not searching · Wi-Fi remote connected",
            obcLinkLabel(ObcLink(ObcLink.Phase.Off, detail = "Not searching · Wi-Fi remote connected")))
        assertEquals("Searching…", obcLinkLabel(ObcLink(ObcLink.Phase.Searching)))
        assertEquals("Connection closed · reconnecting",
            obcLinkLabel(ObcLink(ObcLink.Phase.Searching, "BikeControl", "Connection closed · reconnecting")))
        assertEquals("Connecting to BikeControl", obcLinkLabel(ObcLink(ObcLink.Phase.Connecting, "BikeControl")))
        assertEquals("Connected · BikeControl", obcLinkLabel(ObcLink(ObcLink.Phase.Connected, "BikeControl")))
        assertEquals("Bluetooth scan failed (2)", obcLinkLabel(ObcLink(ObcLink.Phase.Unavailable, detail = "Bluetooth scan failed (2)")))
    }
}
