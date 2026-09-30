package com.spop.poverlay.sensor.interfaces

import android.os.SystemClock
import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

enum class EmulatedModel(val label: String) {
    Bike("Bike"), BikePlus("Bike+"), CrossTrainer("CrossTrainer"), Tread("Tread")
}

/** Explicit developer emulation. Never binds a Peloton service or writes a physical motor. */
class EmulatedSensorInterface(val model: EmulatedModel) : SensorInterface {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override val deviceType = if (model == EmulatedModel.Tread) DeviceType.Tread else DeviceType.Bike
    override val power = MutableStateFlow(150f)
    override val cadence = MutableStateFlow(if (deviceType == DeviceType.Tread) 0f else 80f)
    override val resistance = MutableStateFlow(if (deviceType == DeviceType.Tread) 0f else 40f)
    override val speed = MutableStateFlow(if (deviceType == DeviceType.Tread) 6.2f else 19.5f)
    override val incline = MutableStateFlow(if (deviceType == DeviceType.Tread) 3f else 0f)
    override val bikeControl = if (model == EmulatedModel.BikePlus || model == EmulatedModel.CrossTrainer)
        BikeControl(true, SystemClock::elapsedRealtime) { resistance.value = it.toFloat(); true } else null
    init {
        bikeControl?.acceptSample(BikeSample(power.value, cadence.value, resistance.value.toInt(), SystemClock.elapsedRealtime()))
        bikeControl?.localSimulation()
        scope.launch {
            var phase = 0f
            while (isActive) {
                phase += .1f
                val variation = kotlin.math.sin(phase)
                power.value = if (deviceType == DeviceType.Bike) resistance.value * 3.75f + variation else 150f + variation
                cadence.value = if (deviceType == DeviceType.Bike) 80f + variation else 0f
                speed.value = if (deviceType == DeviceType.Tread) 6.2f + variation * .01f else 19.5f + variation * .01f
                bikeControl?.acceptSample(BikeSample(power.value, cadence.value, resistance.value.toInt(), SystemClock.elapsedRealtime()))
                bikeControl?.tick()
                delay(200)
            }
        }
    }
    fun stop() { bikeControl?.stop(); scope.cancel() }
}
