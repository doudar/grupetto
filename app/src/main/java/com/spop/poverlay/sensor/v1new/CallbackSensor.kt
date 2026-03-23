package com.spop.poverlay.sensor.v1new

import android.os.IBinder
import android.os.Parcel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import com.spop.poverlay.sensor.BikeData

/**
 * Abstract sensor class that works with Peloton's callback-based system
 * Instead of polling, it registers a callback and receives data updates
 */
abstract class CallbackSensor(
    private val binder: IBinder,
    private val interfaceDescriptor: String,
    private val registerCallbackCode: Int,
    private val unregisterCallbackCode: Int
) {
    companion object {
        private const val TAG = "CallbackSensor"
    }
    
    private val mutableSensorValue = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    val sensorValue = mutableSensorValue.asSharedFlow()
    
    private var isRegistered = false
    
    // Abstract method to extract the specific value from BikeData
    protected abstract fun extractValue(bikeData: BikeData): Float
    
    fun start() {
        if (isRegistered) {
            Timber.w("Sensor already started")
            return
        }
        
        try {
            registerCallback()
            isRegistered = true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start callback sensor")
        }
    }
    
    fun stop() {
        if (!isRegistered) {
            return
        }
        
        try {
            unregisterCallback()
            isRegistered = false
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop callback sensor")
        }
    }
    
    private fun registerCallback() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        
        try {
            data.writeInterfaceToken(interfaceDescriptor)
            data.writeStrongBinder(createCallback())
            data.writeString("Grupetto") // Add identifier like the working version
            
            val success = binder.transact(registerCallbackCode, data, reply, 0)
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
            data.writeInterfaceToken(interfaceDescriptor)
            data.writeStrongBinder(createCallback())
            data.writeString("Grupetto") // Add identifier like the working version
            
            val success = binder.transact(unregisterCallbackCode, data, reply, 0)
            if (success) {
                reply.readException()
            }
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering callback")
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
                        data.enforceInterface("com.onepeloton.affernetservice.IV1Callback")
                        
                        val hasData = data.readInt()
                        
                        val bikeData = if (hasData != 0) {
                            // Use the same pattern as the working V1Binding
                            BikeData.CREATOR.createFromParcel(data)
                        } else {
                            null
                        }
                        
                        if (bikeData != null) {
                            val value = extractValue(bikeData)
                            mutableSensorValue.tryEmit(value)
                            Timber.i("Received sensor data: $value")
                        }
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing sensor data")
                        false
                    }
                }
                2 -> { // onSensorError
                    try {
                        data.enforceInterface("com.onepeloton.affernetservice.IV1Callback")
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
                        data.enforceInterface("com.onepeloton.affernetservice.IV1Callback")
                        val status = data.readInt()
                        val success = data.readInt() != 0
                        val errorCode = data.readLong()
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
