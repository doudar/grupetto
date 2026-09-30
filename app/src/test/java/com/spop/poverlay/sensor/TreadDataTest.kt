package com.spop.poverlay.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Drives the real [TreadData] field-order parser against golden parcels captured
 * from a live Peloton Tread (idle, belt stopped).
 *
 * Android's [android.os.Parcel] is not available in local JVM unit tests, and
 * Robolectric's shadow Parcel cannot decode bytes marshalled by a real device.
 * So this test supplies a [TreadData.ParcelSource] that decodes the actual Android
 * parcel wire format from the captured bytes. This exercises the exact same
 * 76-field read order that the on-device [android.os.Parcel] path uses.
 *
 * The key self-check is `dataAvail() == 0` after the 76-field read: this is what
 * caught the missing trailing field on hardware.
 */
class TreadDataTest {

    /**
     * Decodes the little-endian, 4-byte-aligned Android Parcel wire format from a
     * captured byte array. Only the primitive read ops [TreadData] needs are
     * implemented.
     */
    private class WireParcelSource(bytes: ByteArray) : TreadData.ParcelSource {
        private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        override fun readInt(): Int = buf.int

        override fun readLong(): Long = buf.long

        override fun readString(): String? {
            val len = buf.int
            if (len < 0) return null
            val chars = CharArray(len)
            for (i in 0 until len) {
                chars[i] = buf.short.toInt().and(0xFFFF).toChar()
            }
            // null terminator
            buf.short
            align4()
            return String(chars)
        }

        override fun createByteArray(): ByteArray? {
            val len = buf.int
            if (len < 0) return null
            val out = ByteArray(len)
            buf.get(out)
            align4()
            return out
        }

        override fun createIntArray(): IntArray? {
            val len = buf.int
            if (len < 0) return null
            val out = IntArray(len)
            for (i in 0 until len) {
                out[i] = buf.int
            }
            return out
        }

        override fun dataAvail(): Int = buf.remaining()

        private fun align4() {
            val rem = buf.position() % 4
            if (rem != 0) {
                buf.position(buf.position() + (4 - rem))
            }
        }
    }

    private fun readFixture(name: String): ByteArray {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("tread/$name")) {
            "Missing test fixture tread/$name"
        }
        return stream.use { it.readBytes() }
    }

    /**
     * The captured parcel is the full `data` parcel passed to
     * `ITreadCallback.onSensorDataChange`: interface token, then the null-marker int,
     * then the TreadData body. Positions the source just before the TreadData body,
     * exactly like the production callback does (enforceInterface + readInt) before
     * calling `TreadData.CREATOR.createFromParcel`.
     */
    private fun sourceAtBody(bytes: ByteArray): WireParcelSource {
        val source = WireParcelSource(bytes)
        // enforceInterface: strict-mode policy int, work-source int, descriptor string
        source.readInt()
        source.readInt()
        source.readString()
        // null marker (1 = body present)
        val nullMarker = source.readInt()
        assertNotEquals("expected non-null TreadData body", 0, nullMarker)
        return source
    }

    @Test
    fun `parses all 76 fields leaving no bytes remaining`() {
        for (name in listOf("idle_0.parcel", "idle_1.parcel", "idle_2.parcel")) {
            val source = sourceAtBody(readFixture(name))
            TreadData(source)
            assertEquals("dataAvail should be 0 after reading 76 fields ($name)", 0, source.dataAvail())
        }
    }

    @Test
    fun `parses known idle values`() {
        val data = TreadData(sourceAtBody(readFixture("idle_0.parcel")))

        // Confirmed idle values from research doc and stage3.log seq=1
        assertEquals(125, data.mcbMaxSpeed)
        assertEquals(150, data.mcbMaxIncline)
        assertEquals(13230L, data.mcbTotalMiles)
        assertEquals(1, data.mcbSpeedUnit)

        // Belt stopped at idle
        assertEquals(0, data.mcbCurrentSpeed)
        assertEquals(0, data.mcbCurrentIncline)
        assertEquals(0, data.mcbRpm)
        assertEquals(0, data.warningCode)

        // Idle state: NORMAL mcb, SC awake, PIN control-locked
        assertEquals(0, data.mcbState)
        assertEquals(2, data.scSystemState)
        assertEquals(true, data.isTreadLocked)
    }
}
