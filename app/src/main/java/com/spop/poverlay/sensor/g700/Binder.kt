package com.spop.poverlay.sensor.g700

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

// G700 CrossTrainer uses a different service architecture than Bike+
// Based on stellarhopper's findings in issue #38:
// The G700 uses com.onepeloton.workoutservices.metrics.MetricsService
// instead of the old com.onepeloton.affernetservice
const val SERVICE_ACTION = "com.onepeloton.workoutservices.metrics.MetricsService"
private const val SERVICE_PACKAGE = "com.onepeloton.workoutservices.app"

suspend fun getG700Binder(context: Context) = suspendCoroutine<IBinder> { ctx ->
    context.bindService(
        Intent().apply {
            component = ComponentName(
                SERVICE_PACKAGE,
                SERVICE_ACTION
            )
        }, object : ServiceConnection {
            override fun onServiceConnected(p0: ComponentName?, iBinder: IBinder?) {
                Timber.i("G700 sensor service connected $p0")
                if(iBinder == null){
                    Timber.i("G700 sensor service resolution failed $p0")
                    ctx.resumeWithException(Exception("G700 sensor service resolution failed"))
                }else{
                    ctx.resume(iBinder)
                }
            }

            override fun onBindingDied(name: ComponentName?) {
                super.onBindingDied(name)
                Timber.i("G700 sensor service binding died $name")
            }
            override fun onNullBinding(name: ComponentName?) {
                Timber.i("G700 sensor service null binding $name")
            }

            override fun onServiceDisconnected(p0: ComponentName?) {
                Timber.i("G700 sensor service disconnected $p0")
            }

        }, Context.BIND_AUTO_CREATE)
}
