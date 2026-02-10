package com.spop.poverlay.sensor.v2

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.BikeData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val READ_DELAY = 200L

/**
 * Counts consecutive errors, and throws an exception if the limit is reached
 * This is used to stop the sensor if it is not responding.
 * While testing, the sensor would sometimes stop responding randomly, and this was a way to handle that
 * It is not a perfect solution, but it is better than nothing.
 */
class ConsecutiveErrorCounter(private val limit: Int = 5) {
    private var consecutiveErrors = 0

    fun increment() {
        consecutiveErrors++
        if (consecutiveErrors >= limit) {
            throw Exception("Too many consecutive errors")
        }
    }

    fun reset() {
        consecutiveErrors = 0
    }
}

class BikePlusCombinedSensor(private val binder: IBinder) {

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

    private val errorCounter = ConsecutiveErrorCounter()
    private var threadRunning = AtomicBoolean(false)

    fun start() {
        if (threadRunning.getAndSet(true)) return

        thread(name = "BikePlusSensorPoller") {
            Timber.i("Starting BikePlus polling thread")
            try {
                while (threadRunning.get()) {
                    val parcel = Parcel.obtain()
                    val parcel2 = Parcel.obtain()
                    try {
                        parcel.writeInterfaceToken(SERVICE_ACTION)
                        // Transact code 14 fetches the full BikeData
                        binder.transact(14, parcel, parcel2, 0)
                        parcel2.readException()
                        // Skip the first integer
                        parcel2.readInt()
                        
                        val bikeData = BikeData.CREATOR.createFromParcel(parcel2)
                        
                        // Emit values
                        // Power is divided by 100 in original BikePlusPowerSensor
                        // Note: Property access 'rpm' vs 'RPM' depends on interop, sticking to existing convention
                        mutablePower.tryEmit(bikeData.power.toFloat() / 100f)
                        mutableCadence.tryEmit(bikeData.rpm.toFloat())
                        mutableResistance.tryEmit(bikeData.targetResistance.toFloat())

                        errorCounter.reset()
                    } catch (e: Exception) {
                        try {
                            errorCounter.increment()
                        } catch (e: Exception) {
                            Timber.e(e, "BikePlusCombinedSensor stopped due to errors")
                            stop()
                            break
                        }
                    } finally {
                        parcel.recycle()
                        parcel2.recycle()
                    }
                    Thread.sleep(READ_DELAY)
                }
            } catch (e: Exception) {
                Timber.e(e, "BikePlusCombinedSensor thread crashed")
            } finally {
                threadRunning.set(false)
                Timber.i("BikePlus polling thread stopped")
            }
        }
    }

    fun stop() {
        threadRunning.set(false)
    }
}
