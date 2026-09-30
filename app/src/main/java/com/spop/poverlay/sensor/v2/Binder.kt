package com.spop.poverlay.sensor.v2

import android.content.Context
import android.content.Intent
import com.spop.poverlay.sensor.bindBikeService

const val SERVICE_ACTION = "com.onepeloton.affernetservice.IBikeInterface"

suspend fun getV2Binder(
    context: Context,
    intentFactory: () -> Intent = {
        Intent(SERVICE_ACTION).setPackage("com.onepeloton.affernetservice")
    }
) = bindBikeService(context, intentFactory)
