package com.spop.poverlay.antplus

import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import com.dsi.ant.AntService
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows

class AntPlusHandlerLifecycleTest {
    private val context = mockk<Context>(relaxed = true)
    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var handler: AntPlusHandler

    @Before fun setup() {
        mockkStatic(SystemClock::class, AntService::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        every { AntService.bindService(context, any()) } returns true
        handler = AntPlusHandler(context, "Test", scope)
    }

    @After fun cleanup() {
        scope.cancel()
        handler.shutdown()
        unmockkStatic(SystemClock::class, AntService::class)
    }

    @Test fun `disconnect still releases binding exactly once at shutdown`() {
        handler.initialize()
        handler.onServiceDisconnected(null)
        handler.shutdown()
        handler.shutdown()
        verify(exactly = 1) { context.unbindService(handler) }
    }

    @Test fun `late connection after shutdown is ignored`() {
        handler.initialize()
        handler.shutdown()
        val binder = mockk<IBinder>()
        handler.onServiceConnected(null, binder)
        verify { binder wasNot Called }
        verify(exactly = 1) { context.unbindService(handler) }
    }

    @Test fun `null binder is still unbound on shutdown`() {
        handler.initialize()
        handler.onServiceConnected(null, null)
        handler.shutdown()
        verify(exactly = 1) { context.unbindService(handler) }
    }

    @Test fun `failed bind is reported to session owner`() {
        every { AntService.bindService(context, any()) } returns false
        assertThrows(IllegalStateException::class.java) { handler.initialize() }
    }

    @Test fun `cancelled scope cannot bind or retry`() {
        scope.cancel()
        handler.initialize()
        handler.retryChannelSetupIfNeeded()
        verify(exactly = 0) { AntService.bindService(any(), any()) }
    }
}
