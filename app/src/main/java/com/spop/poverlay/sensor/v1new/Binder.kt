package com.spop.poverlay.sensor.v1new

import android.content.Context
import android.content.Intent
import com.spop.poverlay.sensor.bindBikeService

const val SERVICE_ACTION = "com.onepeloton.affernetservice.IV1Interface"

suspend fun getV1NewBinder(
    context: Context,
    intentFactory: () -> Intent = {
        Intent(SERVICE_ACTION).setPackage("com.onepeloton.affernetservice")
    }
) = bindBikeService(context, intentFactory)
