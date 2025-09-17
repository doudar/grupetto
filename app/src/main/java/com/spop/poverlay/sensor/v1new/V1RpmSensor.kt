package com.spop.poverlay.sensor.v1new

import android.os.IBinder
import com.spop.poverlay.sensor.BikeData

class V1NewRpmSensor(binder: IBinder) : CallbackSensor(
    binder = binder,
    interfaceDescriptor = "com.onepeloton.affernetservice.IV1Interface",
    registerCallbackCode = 1,
    unregisterCallbackCode = 2
) {
    override fun extractValue(bikeData: BikeData): Float = bikeData.rpm.toFloat()
}
