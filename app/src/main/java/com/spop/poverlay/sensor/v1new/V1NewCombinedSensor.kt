package com.spop.poverlay.sensor.v1new

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.BikeData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

class V1NewCombinedSensor(
    private val binder: IBinder
) {
    private val mutablePower = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val power = mutablePower.asSharedFlow()

    private val mutableCadence = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val cadence = mutableCadence.asSharedFlow()

    private val mutableResistance = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val resistance = mutableResistance.asSharedFlow()

    private var isRegistered = false
    private val callbackBinder = createCallback()

    companion object {
        private const val INTERFACE_DESCRIPTOR = "com.onepeloton.affernetservice.IV1Interface"
        private const val CALLBACK_DESCRIPTOR = "com.onepeloton.affernetservice.IV1Callback"
        private const val REGISTER_CODE = 1
        private const val UNREGISTER_CODE = 2
    }

    fun start() {
        if (isRegistered) {
            Timber.w("V1NewCombinedSensor already started")
            return
        }
        try {
            registerCallback()
            isRegistered = true
            Timber.d("V1NewCombinedSensor started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start V1NewCombinedSensor")
        }
    }

    fun stop() {
        if (!isRegistered) return
        try {
            unregisterCallback()
            isRegistered = false
            Timber.d("V1NewCombinedSensor stopped successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop V1NewCombinedSensor")
        }
    }

    private fun registerCallback() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeString("Grupetto")
            
            Timber.d("Registering callback with interface: $INTERFACE_DESCRIPTOR")
            val success = binder.transact(REGISTER_CODE, data, reply, 0)
            if (success) {
                reply.readException()
                Timber.i("Successfully registered callback")
            } else {
                throw Exception("Failed to register callback")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun unregisterCallback() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeString("Grupetto")
            
            val success = binder.transact(UNREGISTER_CODE, data, reply, 0)
            if (success) {
                reply.readException()
                Timber.d("Successfully unregistered callback")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun createCallback() = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                1 -> { // onSensorDataChange
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        
                        val hasData = data.readInt()
                        
                        val bikeData = if (hasData != 0) {
                            BikeData.CREATOR.createFromParcel(data)
                        } else {
                            null
                        }
                        
                        if (bikeData != null) {
                            // Power is divided by 100.0f
                            mutablePower.tryEmit(bikeData.power.toFloat() / 100.0f)
                            // RPM
                            mutableCadence.tryEmit(bikeData.rpm.toFloat())
                            // Resistance see V1ResistanceSensor.kt
                            mutableResistance.tryEmit(bikeData.currentResistance.toFloat())
                        }
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing sensor data")
                        false
                    }
                }
                2 -> { // onSensorError
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val errorCode = data.readLong()
                        Timber.w("Sensor error: $errorCode")
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing sensor error")
                        false
                    }
                }
                3 -> { // onCalibrationStatus
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val status = data.readInt()
                        val success = data.readInt() != 0
                        val errorCode = data.readLong()
                        Timber.d("Calibration status: status=$status success=$success error=$errorCode")
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing calibration status")
                        false
                    }
                }
                else -> {
                    super.onTransact(code, data, reply, flags)
                }
            }
        }
    }
}
