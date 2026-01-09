package com.spop.poverlay.util

import android.app.Service
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Allows a service to act as a LifecycleOwner
 * Mainly intended for use with WindowManager
 */
abstract class LifecycleEnabledService : Service(), LifecycleOwner, CoroutineScope {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override val coroutineContext: CoroutineContext
        get() = coroutineScope.coroutineContext
    protected fun View.lifecycleViaService() {
        // No-op placeholder to keep call sites simple; lifecycle owner is provided by this service.
    }

    private val lifecycleRegistry: LifecycleRegistry by lazy {
        LifecycleRegistry(this)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry


    private fun handleLifecycleEvent(event: Lifecycle.Event) =
        lifecycleRegistry.handleLifecycleEvent(event)

    override fun onCreate() {
        super.onCreate()
        handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        handleLifecycleEvent(Lifecycle.Event.ON_START)
        handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDestroy() {
        super.onDestroy()
        handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        coroutineScope.cancel()
    }
}
