package com.spop.poverlay.calibration

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.v1new.SERVICE_ACTION
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CalibrationServiceTest {
    private val remote = mockk<IBinder>()
    private val data = mockk<Parcel>(relaxed = true)
    private val reply = mockk<Parcel>(relaxed = true)
    @Before fun setup() {
        mockkStatic(Parcel::class)
        every { Parcel.obtain() } returnsMany listOf(data, reply)
        every { remote.transact(11, data, reply, 0) } returns true
        every { reply.readIntArray(any()) } answers {
            val values = firstArg<IntArray>()
            values.indices.forEach { values[it] = 1000 + 100 * it }
        }
    }
    @After fun cleanup() = unmockkStatic(Parcel::class)
    @Test fun readsOutArrayAndNeverSendsAnyWriteTransaction() {
        assertEquals(List(31) { 1000 + 100 * it }, readActiveLxx(remote))
        verifyOrder {
            data.writeInterfaceToken(SERVICE_ACTION)
            data.writeInt(31)
            remote.transact(11, data, reply, 0)
            reply.readException()
            reply.readIntArray(any())
            data.recycle(); reply.recycle()
        }
        verify(exactly = 1) { remote.transact(any(), any(), any(), any()) }
        verify(exactly = 0) { data.writeIntArray(any()) }
    }
    @Test fun rejectedReadAndRemoteExceptionsRecycleParcels() {
        every { remote.transact(11, data, reply, 0) } returns false
        assertTrue(runCatching { readActiveLxx(remote) }.isFailure)
        verify { data.recycle(); reply.recycle() }
    }
    @Test fun invalidTableIsRejected() {
        every { reply.readIntArray(any()) } answers { firstArg<IntArray>().fill(0) }
        assertTrue(runCatching { readActiveLxx(remote) }.isFailure)
    }
}
