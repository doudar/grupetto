package com.spop.poverlay.sensor.v1new

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val SERVICE_ACTION = "com.onepeloton.affernetservice.IV1Interface"
private const val SERVICE_PACKAGE = "com.onepeloton.affernetservice"
private const val SERVICE_INTENT = "com.onepeloton.affernetservice.AffernetService"

/**
 * Holds the connection alongside the binder so callers can later unbind via [Context.unbindService].
 */
class BoundService(val binder: IBinder, val connection: ServiceConnection)

suspend fun getV1NewBinder(context: Context) = suspendCancellableCoroutine<BoundService> { ctx ->
    lateinit var connection: ServiceConnection
    connection = object : ServiceConnection {
        override fun onServiceConnected(p0: ComponentName?, iBinder: IBinder?) {
            Timber.i("V1 sensor service connected $p0")
            if(iBinder == null){
                Timber.i("V1 sensor service resolution failed $p0")
                ctx.resumeWithException(Exception("V1 sensor service resolution failed"))
            }else{
                ctx.resume(BoundService(iBinder, connection))
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Timber.i("V1 sensor service binding died $name")
        }

        override fun onNullBinding(name: ComponentName?) {
            Timber.i("V1 sensor service null binding $name")
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            Timber.i("V1 sensor service disconnected $p0")
        }
    }
    val bound = context.bindService(
        Intent(SERVICE_INTENT).apply {
            setAction(SERVICE_ACTION)
            setPackage(SERVICE_PACKAGE)
        }, connection, Context.BIND_AUTO_CREATE)
    if (!bound) {
        Timber.w("V1 sensor service bindService() returned false")
        ctx.resumeWithException(Exception("V1 sensor service bindService() returned false"))
    } else {
        // If stop() cancels the coroutine before onServiceConnected fires, this unbinds the
        // in-flight connection so it isn't leaked (serviceConnection would otherwise never be
        // assigned back in the caller, so its own unbindService call would be skipped).
        ctx.invokeOnCancellation {
            runCatching { context.unbindService(connection) }
        }
    }
}
