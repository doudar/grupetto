package com.spop.poverlay.sensor

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.spop.poverlay.sensor.v1new.getV1NewBinder
import com.spop.poverlay.sensor.v2.getV2Binder
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BikeServiceBindingTest(private val version: String) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun versions() = listOf(arrayOf("V1"), arrayOf("BikePlus"))
    }
    private val context = mockk<Context>()
    private val connection = slot<ServiceConnection>()
    private val binder = mockk<IBinder>()
    private suspend fun bind(): BoundBikeService = if (version == "V1") {
        getV1NewBinder(context) { mockk<Intent>() }
    } else {
        getV2Binder(context) { mockk<Intent>() }
    }
    private fun setup() {
        every { context.bindService(any(), capture(connection), any<Int>()) } returns true
        every { context.unbindService(any()) } just Runs
    }

    @Test fun `successful connection releases exactly once after explicit close`() = runBlocking {
        setup()
        val result = async(start = CoroutineStart.UNDISPATCHED) { bind() }
        connection.captured.onServiceConnected(null, binder)
        val service = result.await()
        assertSame(binder, service.binder)
        verify(exactly = 0) { context.unbindService(any()) }
        service.close()
        service.close()
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun `cancellation before connection releases and ignores late callback`() = runBlocking {
        setup()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { bind() }
        job.cancelAndJoin()
        connection.captured.onServiceConnected(null, binder)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun `cancellation after resume before delivery releases connection`() = runBlocking {
        setup()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            bind()
            fail("Cancelled binding should not be delivered")
        }
        connection.captured.onServiceConnected(null, binder)
        job.cancelAndJoin()
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun `null binding and repeated callbacks fail and release once`() = runBlocking {
        setup()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { bind() } }
        connection.captured.onNullBinding(null)
        connection.captured.onServiceConnected(null, binder)
        assertTrue(result.await().isFailure)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun `binding death before connection fails and releases`() = runBlocking {
        setup()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { bind() } }
        connection.captured.onBindingDied(null)
        assertTrue(result.await().isFailure)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }

    @Test fun `bind returning false fails instead of waiting indefinitely`() = runBlocking {
        setup()
        every { context.bindService(any(), capture(connection), any<Int>()) } returns false
        assertTrue(runCatching { bind() }.isFailure)
        verify(exactly = 1) { context.unbindService(connection.captured) }
    }
}
