package com.spop.poverlay.sensor.interfaces

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for the bind/unbind lifecycle fix: [PelotonBikeSensorInterfaceV1New.stop]
 * must actually unbind the service it connected to, and must be safe to call more than once.
 */
class PelotonBikeSensorInterfaceV1NewTest {

    @Test
    fun `stop unbinds the connected service exactly once and is safe to call twice`() {
        val context = mockk<Context>()
        val binder = mockk<IBinder>(relaxed = true)
        val unbindCount = AtomicInteger(0)

        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } answers {
            val connection = secondArg<ServiceConnection>()
            connection.onServiceConnected(mockk<ComponentName>(relaxed = true), binder)
            true
        }
        every { context.unbindService(any()) } answers { unbindCount.incrementAndGet(); Unit }

        val sensorInterface = PelotonBikeSensorInterfaceV1New(context)

        // The service connects asynchronously on Dispatchers.IO; stop() only unbinds once that
        // has completed, so poll (calling the idempotent public stop()) until it lands.
        val deadline = System.currentTimeMillis() + 2_000
        while (unbindCount.get() == 0 && System.currentTimeMillis() < deadline) {
            sensorInterface.stop()
            if (unbindCount.get() == 0) Thread.sleep(10)
        }

        assertTrue("Expected unbindService to be called", unbindCount.get() > 0)

        sensorInterface.stop()

        verify(exactly = 1) { context.unbindService(any()) }
    }
}
