package com.spop.poverlay.sensor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

class BoundBikeService(val binder: IBinder, private val release: () -> Unit) : AutoCloseable {
    override fun close() = release()
}

internal suspend fun bindBikeService(context: Context, intentFactory: () -> Intent): BoundBikeService =
    suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        lateinit var connection: ServiceConnection
        fun release() {
            if (released.compareAndSet(false, true)) {
                runCatching { context.unbindService(connection) }
                    .onFailure { Timber.w(it, "Failed to unbind bike service") }
            }
        }
        fun fail(message: String) {
            if (completed.compareAndSet(false, true)) {
                release()
                continuation.resumeWithException(IllegalStateException(message))
            }
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    fail("Bike service returned a null binder")
                } else if (completed.compareAndSet(false, true)) {
                    continuation.resume(BoundBikeService(binder, ::release)) { release() }
                }
            }
            override fun onNullBinding(name: ComponentName?) = fail("Bike service returned a null binding")
            override fun onBindingDied(name: ComponentName?) = fail("Bike service binding died")
            override fun onServiceDisconnected(name: ComponentName?) {
                Timber.w("Bike sensor service disconnected: %s", name)
            }
        }
        if (!context.bindService(intentFactory(), connection, Context.BIND_AUTO_CREATE)) {
            fail("Bike service bindService returned false")
        }
        continuation.invokeOnCancellation {
            if (completed.compareAndSet(false, true)) release()
        }
    }
