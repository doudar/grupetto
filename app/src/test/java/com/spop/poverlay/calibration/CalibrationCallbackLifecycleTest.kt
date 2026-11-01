package com.spop.poverlay.calibration

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CalibrationCallbackLifecycleTest {
    private val remote = mockk<IBinder>()
    private val request = mockk<Parcel>(relaxed = true)
    private val reply = mockk<Parcel>(relaxed = true)
    private val callback = slot<IBinder>()
    private lateinit var service: CalibrationService
    private val frame = CalibrationFrame(1000, 1500, 80.0, 200.0, "bike", 1, 40)

    @Before fun setup() {
        mockkStatic(Parcel::class, SystemClock::class)
        mockkStatic("com.spop.poverlay.calibration.CalibrationServiceKt")
        every { Parcel.obtain() } returnsMany listOf(request, reply, request, reply)
        every { request.writeStrongBinder(capture(callback)) } just Runs
        every { remote.transact(any(), request, reply, 0) } returns true
        every { SystemClock.elapsedRealtime() } returns 1000L
        service = CalibrationService(remote)
        service.start()
    }

    @After fun cleanup() {
        runCatching { service.close() }
        unmockkStatic(Parcel::class, SystemClock::class)
        unmockkStatic("com.spop.poverlay.calibration.CalibrationServiceKt")
    }

    // Invoke the real callback override directly: JVM Android stubs do not implement Binder transport.
    private fun deliver(code: Int, data: Parcel) {
        val method = callback.captured.javaClass.getDeclaredMethod("onTransact",
            Int::class.javaPrimitiveType, Parcel::class.java, Parcel::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        assertEquals(true, method.invoke(callback.captured, code, data, null, IBinder.FLAG_ONEWAY))
    }
    private fun error(code: Long) = mockk<Parcel>(relaxed = true).also {
        every { it.readLong() } returns code
        deliver(2, it)
    }
    private fun frameParcel() = mockk<Parcel>(relaxed = true).also {
        every { it.readInt() } returns 1
        every { readCalibrationFrame(it, any()) } returns frame
    }

    @Test fun startupZeroAllowsFirstFrameAndDoesNotConsumeTheFrameThrottle() {
        val status = error(0L)
        assertNull(service.failure)
        deliver(1, frameParcel())
        assertEquals(frame, service.frame)
        assertNull(service.failure)
        verify(exactly = 1) { status.readLong() }
        verify(exactly = 0) { status.readInt() }
    }

    @Test fun zeroPreservesExistingFrame() {
        deliver(1, frameParcel())
        error(0L)
        assertEquals(frame, service.frame)
        assertNull(service.failure)
    }

    @Test fun nonzeroLongErrorRemainsTerminalAfterZeroAndLaterFrames() {
        deliver(1, frameParcel())
        // The low 32 bits are zero: truncating the AIDL long would incorrectly accept this.
        error(1L shl 32)
        val failure = service.failure
        assertNotNull(failure)
        assertTrue(failure!!.contains("4294967296"))
        assertNull(service.frame)
        error(0L)
        every { SystemClock.elapsedRealtime() } returns 2000L
        deliver(1, frameParcel())
        assertNull(service.frame)
        assertEquals(failure, service.failure)
    }

    @Test fun calibrationStatusStillInvalidatesCapture() {
        deliver(1, frameParcel())
        deliver(3, mockk(relaxed = true))
        assertNull(service.frame)
        assertTrue(service.failure!!.contains("calibration change"))
    }

    @Test fun negativeSensorErrorStillStopsCapture() {
        deliver(1, frameParcel())
        error(-1001L)
        assertNull(service.frame)
        assertTrue(service.failure!!.contains("-1001"))
    }

    @Test fun malformedErrorPayloadFailsClosed() {
        val data = mockk<Parcel>(relaxed = true)
        every { data.readLong() } throws IllegalStateException("truncated long")
        deliver(2, data)
        assertNull(service.frame)
        assertTrue(service.failure!!.contains("could not be decoded"))
    }

    @Test fun closeIgnoresLateFramesAndStatusCallbacks() {
        deliver(1, frameParcel())
        service.close()
        val late = frameParcel()
        deliver(1, late)
        error(-1001L)
        deliver(3, mockk(relaxed = true))
        assertNull(service.frame)
        assertNull(service.failure)
        verify(exactly = 0) { readCalibrationFrame(late, any()) }
    }

    @Test fun failedUnregisterStillClearsFrameAndClosesLocally() {
        deliver(1, frameParcel())
        every { remote.transact(2, request, reply, 0) } throws IllegalStateException("binder died")
        assertTrue(runCatching { service.close() }.isFailure)
        every { SystemClock.elapsedRealtime() } returns 2000L
        deliver(1, frameParcel())
        assertNull(service.frame)
        assertNull(service.failure)
        service.close()
        verify(exactly = 1) { remote.transact(2, request, reply, 0) }
    }

    @Test fun repeatedStartAndCloseDoNotDuplicateRegistrationAndClosedClientsCannotRestart() {
        service.start()
        service.close()
        service.close()
        assertTrue(runCatching { service.start() }.isFailure)
        verify(exactly = 1) { remote.transact(1, request, reply, 0) }
        verify(exactly = 1) { remote.transact(2, request, reply, 0) }
    }

    @Test fun closeWaitsForInFlightDecodeAndClearsItsResult() {
        val decoding = CountDownLatch(1)
        val finishDecode = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val data = frameParcel()
        every { readCalibrationFrame(data, any()) } answers {
            decoding.countDown()
            check(finishDecode.await(5, TimeUnit.SECONDS))
            frame
        }
        val threads = Executors.newFixedThreadPool(2)
        try {
            val delivery = threads.submit { deliver(1, data) }
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            val teardown = threads.submit { closing.countDown(); service.close(); closed.countDown() }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS))
            finishDecode.countDown()
            delivery.get(5, TimeUnit.SECONDS)
            teardown.get(5, TimeUnit.SECONDS)
            assertNull(service.frame)
            assertNull(service.failure)
        } finally {
            finishDecode.countDown()
            threads.shutdownNow()
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
