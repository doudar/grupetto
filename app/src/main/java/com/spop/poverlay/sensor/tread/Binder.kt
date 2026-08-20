package com.spop.poverlay.sensor.tread

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SERVICE_ACTION = "com.onepeloton.affernetservice.ITreadInterface"
private const val SERVICE_PACKAGE = "com.onepeloton.affernetservice"
private const val SERVICE_INTENT = "com.onepeloton.affernetservice.AffernetService"

/**
 * The result of a successful [getTreadBinder] bind. The caller MUST retain the
 * [connection] for the lifetime of the [binder] and later call
 * `context.unbindService(connection)` (exactly once) to release the binding;
 * otherwise the [ServiceConnection] leaks (ServiceConnectionLeaked).
 */
data class TreadBinding(val binder: IBinder, val connection: ServiceConnection)

/**
 * Build the affernet service bind [Intent]. Extracted so unit tests can inject a stub:
 * constructing a real [Intent] against the stubbed android.jar throws, and mocking that
 * away globally (testOptions.unitTests.returnDefaultValues) would silently disable the
 * "Method ... not mocked" guardrail for every test in the module.
 */
internal val defaultTreadServiceIntentFactory: () -> Intent = {
    Intent(SERVICE_INTENT).apply {
        setAction(SERVICE_ACTION)
        setPackage(SERVICE_PACKAGE)
    }
}

suspend fun getTreadBinder(
    context: Context,
    intentFactory: () -> Intent = defaultTreadServiceIntentFactory,
) = suspendCancellableCoroutine<TreadBinding> { ctx ->
    // The service callbacks below can fire more than once (e.g. onServiceConnected
    // succeeds and onBindingDied fires later), and resuming a continuation twice throws
    // IllegalStateException. Guard so the first of {connected, null binding, died} wins
    // and every later callback is a no-op.
    val resumed = AtomicBoolean(false)
    val connection = object : ServiceConnection {
        private fun resumeOnce(block: (Continuation<TreadBinding>) -> Unit) {
            if (resumed.compareAndSet(false, true)) {
                block(ctx)
            }
        }

        override fun onServiceConnected(p0: ComponentName?, iBinder: IBinder?) {
            Timber.i("Tread sensor service connected $p0")
            if (iBinder == null) {
                Timber.i("Tread sensor service resolution failed $p0")
                resumeOnce { it.resumeWithException(Exception("Tread sensor service resolution failed")) }
            } else {
                resumeOnce { it.resume(TreadBinding(iBinder, this)) }
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Timber.i("Tread sensor service binding died $name")
            resumeOnce { it.resumeWithException(Exception("Tread sensor service resolution failed")) }
        }

        override fun onNullBinding(name: ComponentName?) {
            Timber.i("Tread sensor service null binding $name")
            resumeOnce { it.resumeWithException(Exception("Tread sensor service resolution failed")) }
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Timber.i("Tread sensor service disconnected $p0")
        }
    }

    val bound = context.bindService(
        intentFactory(), connection, Context.BIND_AUTO_CREATE
    )
    // bindService returns false when the bind could not even be initiated; no callback
    // will ever arrive, so resume with an exception (and unbind to avoid leaking the
    // connection) instead of hanging the coroutine forever.
    if (!bound) {
        Timber.i("Tread sensor service bind could not be initiated")
        context.unbindService(connection)
        if (resumed.compareAndSet(false, true)) {
            ctx.resumeWithException(Exception("Tread sensor service bind could not be initiated"))
        }
        return@suspendCancellableCoroutine
    }

    // The bind is now in flight. If the caller's scope is cancelled before any callback
    // arrives (e.g. PelotonTreadSensorInterface.stop() -> job.cancelChildren() during a
    // quick restart) no callback will ever resume us, so unbind here or the
    // ServiceConnection leaks (ServiceConnectionLeaked) and keeps AffernetService bound.
    // The same `resumed` flag the callbacks use guarantees this runs at most once and
    // never after a callback already won the race (that path hands the unbind to the
    // caller via TreadBinding, or has already unbound itself).
    ctx.invokeOnCancellation {
        if (resumed.compareAndSet(false, true)) {
            Timber.i("Tread sensor service bind cancelled in flight; unbinding")
            runCatching { context.unbindService(connection) }
                .onFailure { Timber.w(it, "Unbind after cancelled tread bind failed") }
        }
    }
}
