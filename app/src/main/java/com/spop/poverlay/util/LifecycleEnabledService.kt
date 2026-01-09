package com.spop.poverlay.util

import android.app.Service
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/**
 * Allows a service to act as a LifecycleOwner
 * Mainly intended for use with WindowManager
 */
abstract class LifecycleEnabledService : Service(), LifecycleOwner, SavedStateRegistryOwner, CoroutineScope {

    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    override val coroutineContext: CoroutineContext
        get() = coroutineScope.coroutineContext
    
    private val savedStateRegistryController: SavedStateRegistryController by lazy {
        SavedStateRegistryController.create(this)
    }

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    protected fun View.lifecycleViaService() {
        ViewTreeLifecycleOwner.set(this, this@LifecycleEnabledService)
        setViewTreeSavedStateRegistryOwner(this@LifecycleEnabledService)
    }

    private val lifecycleRegistry: LifecycleRegistry by lazy {
        LifecycleRegistry(this)
    }

    // Provide LifecycleOwner implementation explicitly to avoid override issues
    override fun getLifecycle(): Lifecycle = lifecycleRegistry


    private fun handleLifecycleEvent(event: Lifecycle.Event) =
        lifecycleRegistry.handleLifecycleEvent(event)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
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
