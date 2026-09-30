package com.spop.poverlay.antplus

import android.os.SystemClock
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class AntPlusPayloadTest {
    private var now = 0L
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var handler: AntPlusHandler

    @Before fun setup() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { now }
        handler = AntPlusHandler(mockk(), "Test", scope)
    }

    @After fun cleanup() {
        scope.cancel()
        unmockkStatic(SystemClock::class)
    }

    private fun payload(method: String): ByteArray = AntPlusHandler::class.java
        .getDeclaredMethod(method).apply { isAccessible = true }.invoke(handler)
        .let { (it as ByteArray).copyOf() }

    private fun csc(speed: Float = 0f): ByteArray = AntPlusHandler::class.java
        .getDeclaredMethod("buildCscSpeedCadencePage", Float::class.javaPrimitiveType)
        .apply { isAccessible = true }.invoke(handler, speed).let { (it as ByteArray).copyOf() }

    private fun assertBytes(actual: ByteArray, vararg expected: Int) =
        assertArrayEquals(expected.map { it.toByte() }.toByteArray(), actual)

    @Test fun `combined CSC packs four little endian uint16 values without page byte`() {
        handler.broadcastPowerData(250, 60)
        now = 1000
        assertBytes(csc(36f), 0, 4, 1, 0, 0x67, 3, 4, 0)
    }

    @Test fun `crank counter exceeds 255 and wraps at 65536`() {
        handler.broadcastPowerData(250, 60)
        now = 256_000
        assertBytes(csc(), 0, 0, 0, 1, 0, 0, 0, 0)
        now = 65_537_000
        assertBytes(csc(), 0, 4, 1, 0, 0, 0, 0, 0)
    }

    @Test fun `fractional revolutions accumulate and stopping freezes counters`() {
        handler.broadcastPowerData(250, 60)
        now = 500
        assertBytes(csc(), 0, 0, 0, 0, 0, 0, 0, 0)
        now = 1000
        val moving = csc()
        assertBytes(moving, 0, 4, 1, 0, 0, 0, 0, 0)
        handler.broadcastPowerData(0, 0)
        now = 5000
        assertArrayEquals(moving, csc())
    }

    @Test fun `power page preserves cadence power and accumulated power`() {
        handler.broadcastPowerData(300, 90)
        assertBytes(payload("buildPowerPayload"), 0x10, 0, 0, 0, 0, 0, 0, 0)
        assertBytes(payload("buildPowerPayload"), 0x10, 1, 255, 90, 0x2c, 1, 0x2c, 1)
        assertBytes(payload("buildPowerPayload"), 0x10, 2, 255, 90, 0x58, 2, 0x2c, 1)
    }

    @Test fun `common power pages do not leave stale bytes in reused buffer`() {
        handler.broadcastPowerData(300, 90)
        payload("buildPowerPayload")
        repeat(14) { payload("buildPowerPayload") }
        assertEquals(0x50, payload("buildPowerPayload")[0].toInt())
        assertBytes(payload("buildPowerPayload"), 0x10, 15, 255, 90, 0x94, 0x11, 0x2c, 1)
        repeat(13) { payload("buildPowerPayload") }
        assertEquals(0x51, payload("buildPowerPayload")[0].toInt())
        assertEquals(0x10, payload("buildPowerPayload")[0].toInt())
    }

    @Test fun `heart rate beats advance and cleared reading stops stale BPM`() {
        handler.broadcastHrmData(120)
        now = 1000
        assertBytes(payload("buildHrmPayload"), 0, 255, 255, 255, 0, 4, 2, 120)
        handler.broadcastHrmData(0)
        now = 2000
        assertBytes(payload("buildHrmPayload"), 0, 255, 255, 255, 0, 4, 2, 0)
    }
}
