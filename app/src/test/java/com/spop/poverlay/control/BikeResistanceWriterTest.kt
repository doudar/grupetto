package com.spop.poverlay.control

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.v2.SERVICE_ACTION
import com.spop.poverlay.sensor.v2.writeBikeResistance
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BikeResistanceWriterTest {
    private val binder = mockk<IBinder>()
    private val data = mockk<Parcel>(relaxed = true)
    private val reply = mockk<Parcel>(relaxed = true)
    @Before fun setup() {
        mockkStatic(Parcel::class)
        every { Parcel.obtain() } returnsMany listOf(data, reply)
        every { binder.transact(7, data, reply, 0) } returns true
    }
    @After fun cleanup() { unmockkStatic(Parcel::class) }
    @Test fun writesInterfaceTokenAndResistanceAndReadsException() {
        assertTrue(writeBikeResistance(binder, 42))
        verifyOrder {
            data.writeInterfaceToken(SERVICE_ACTION)
            data.writeInt(42)
            binder.transact(7, data, reply, 0)
            reply.readException()
            data.recycle()
            reply.recycle()
        }
    }
    @Test fun rejectedTransactionReturnsFailureAndRecyclesBothParcels() {
        every { binder.transact(7, data, reply, 0) } returns false
        assertFalse(writeBikeResistance(binder, 42))
        verify(exactly = 0) { reply.readException() }
        verify { data.recycle(); reply.recycle() }
    }
    @Test fun remoteExceptionReturnsFailureAndRecyclesBothParcels() {
        every { reply.readException() } throws SecurityException("Denied")
        assertFalse(writeBikeResistance(binder, 42))
        verify { data.recycle(); reply.recycle() }
    }
    @Test fun binderDeathReturnsFailureAndRecyclesBothParcels() {
        every { binder.transact(7, data, reply, 0) } throws IllegalStateException("Disconnected")
        assertFalse(writeBikeResistance(binder, 42))
        verify { data.recycle(); reply.recycle() }
    }
    @Test fun invalidResistanceNeverTouchesBinder() {
        assertFalse(writeBikeResistance(binder, -1))
        assertFalse(writeBikeResistance(binder, 101))
        verify(exactly = 0) { Parcel.obtain(); binder.transact(any(), any(), any(), any()) }
    }
}
