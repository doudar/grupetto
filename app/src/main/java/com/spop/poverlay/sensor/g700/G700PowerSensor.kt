package com.spop.poverlay.sensor.g700

import android.os.IBinder

class G700PowerSensor(binder: IBinder) : G700Sensor(binder) {
    override fun mapValue(value: Float) = value / 100
    override val sensorType = "Power"
}
