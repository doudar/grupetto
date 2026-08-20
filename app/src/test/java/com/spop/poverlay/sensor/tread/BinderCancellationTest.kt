package com.spop.poverlay.sensor.tread

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [getTreadBinder] must be cancellation aware: PelotonTreadSensorInterface.stop()
 * cancels the scope, and if that happens while the bind is still in flight the
 * ServiceConnection would otherwise leak (ServiceConnectionLeaked) and keep the
 * bound AffernetService alive on a motorized treadmill.
 */
class BinderCancellationTest {

    // A real Intent cannot be constructed against the stubbed android.jar, and relaxing
    // that module-wide would disable the "Method ... not mocked" guardrail everywhere.
    // getTreadBinder takes an injectable factory instead; the Intent is never inspected
    // because bindService itself is mocked.
    private fun stubIntentFactory(): () -> Intent = { mockk(relaxed = true) }

    @Test(timeout = 15_000)
    fun `cancelling an in-flight bind unbinds exactly once`() = runBlocking {
        val bindCalled = CountDownLatch(1)
        val unbindCount = AtomicInteger(0)
        val context = mockk<Context>()
        every { context.bindService(any(), any<ServiceConnection>(), any<Int>()) } answers {
            bindCalled.countDown()
            true
        }
        every { context.unbindService(any()) } answers {
            unbindCount.incrementAndGet()
            Unit
        }

        val job = launch(Dispatchers.Default) { getTreadBinder(context, stubIntentFactory()) }
        assertTrue("bindService was never called", bindCalled.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertEquals(1, unbindCount.get())
    }

    @Test(timeout = 15_000)
    fun `cancelling after a successful bind does not unbind again`() = runBlocking {
        val unbindCount = AtomicInteger(0)
        val connectionSlot = slot<ServiceConnection>()
        val binder = mockk<IBinder>()
        val context = mockk<Context>()
        every {
            context.bindService(any(), capture(connectionSlot), any<Int>())
        } answers {
            // Service connects synchronously; the continuation resumes with the binding
            // and the caller now owns the unbind.
            connectionSlot.captured.onServiceConnected(null, binder)
            true
        }
        every { context.unbindService(any()) } answers {
            unbindCount.incrementAndGet()
            Unit
        }

        val job = launch(Dispatchers.Default) { getTreadBinder(context, stubIntentFactory()) }
        job.join()
        job.cancelAndJoin()

        assertEquals(0, unbindCount.get())
    }
}
